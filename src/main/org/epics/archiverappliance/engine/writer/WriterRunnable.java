
/*******************************************************************************
 * Copyright (c) 2011 The Board of Trustees of the Leland Stanford Junior University
 * as Operator of the SLAC National Accelerator Laboratory.
 * Copyright (c) 2011 Brookhaven National Laboratory.
 * EPICS archiver appliance is distributed subject to a Software License Agreement found
 * in file LICENSE that is included with this distribution.
 *******************************************************************************/

package org.epics.archiverappliance.engine.writer;

import java.io.IOException;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.epics.archiverappliance.common.BasicContext;
import org.epics.archiverappliance.config.ConfigService;
import org.epics.archiverappliance.data.DBRTimeEvent;
import org.epics.archiverappliance.engine.membuf.ArrayListEventStream;
import org.epics.archiverappliance.engine.model.ArchiveChannel;
import org.epics.archiverappliance.engine.model.SampleBuffer;
import org.epics.archiverappliance.engine.model.YearListener;

/**
 * WriterRunnable is scheduled by the executor in the engine context every writing period.
 * @author Luofeng Li
 *
 */
public class WriterRunnable implements Runnable {
	private static final Logger logger = LogManager.getLogger(WriterRunnable.class);
	/** Minimum write period [seconds] */
	private static final double MIN_WRITE_PERIOD = 1.0;

	private final int maxWriteSkips;

	private final double minWriteRatio;

	private double writePeriod;

    /**the sample buffer hash map*/
	final private ConcurrentHashMap<String, SampleBuffer> buffers = new ConcurrentHashMap<String, SampleBuffer>();

	/**the configservice used by this WriterRunnable*/
	private ConfigService configservice = null;
	/**is running?*/
	private boolean isRunning=false;

	final private ConcurrentHashMap<String, Integer> skipCounter = new ConcurrentHashMap<>();

/**
 * the constructor
 * @param configservice the configservice used by this WriterRunnable
 */
	public WriterRunnable(ConfigService configservice) {

		this.configservice = configservice;
		this.writePeriod = MIN_WRITE_PERIOD;

		this.minWriteRatio = Double.parseDouble(configservice.getInstallationProperties().getProperty("org.epics.archiverappliance.config.PVTypeInfo.minWriteRatio", "0.7"));
		logger.info("Minimum buffer fill for writing is " + this.minWriteRatio);

		this.maxWriteSkips = Integer.parseInt(configservice.getInstallationProperties().getProperty("org.epics.archiverappliance.config.PVTypeInfo.maxWriteSkips", "30"));
		logger.info("Maximum number of skips is " + this.maxWriteSkips);
	}

	/** Add a channel's buffer that this thread reads 
	 * @param channel ArchiveChannel
	 */
	public void addChannel(final ArchiveChannel channel) {
		addSampleBuffer(channel.getName(), channel.getSampleBuffer());
	}
/**
 * remove one sample buffer from the buffer hash map.
 * At the same time. it also removes the channel from the channel hash map in the engine context
 * @param channelName the name of the channel who and whose sample buffer are removed
 */
	public void removeChannel(final String channelName) {
		buffers.remove(channelName);
	}

	/**
	 * add sample buffer into this writer runnable and add year listener to each sample buffer
	 * @param name the name of the channel
	 * @param buffer the sample buffer for this channel
	 */
	void addSampleBuffer(final String name, final SampleBuffer buffer) {
		// buffers.add(buffer);
		buffers.put(name, buffer);
		buffer.addYearListener(new YearListener() {

			@Override
			public void yearChanged(final SampleBuffer sampleBuffer) {
				//
				configservice.getEngineContext().getScheduler().execute(new Runnable(){

					@Override
					public void run() {
						try {
							write(sampleBuffer);
							logger.info(sampleBuffer.getChannelName() + ":year change");
						} catch (IOException e) {
							logger.error("Exception", e);
						}
						
					}
					
				});
				
			}

		});
	}

/**
 * set the writing period. when the writing period is at least 10 seonds.
 * When write_period &lt; 10 , the writing period is 10 seconds actually.
 * @param write_period  the writing period in second
 * @return the actual writing period in second
 */
	public double setWritingPeriod(double write_period) {
		double tempwrite_period=write_period;
		if (tempwrite_period < MIN_WRITE_PERIOD) {
		
			tempwrite_period = MIN_WRITE_PERIOD;
		}
		this.writePeriod = tempwrite_period;
		return tempwrite_period;
		
	}
	

  
	@Override
	public void run() {
		try {
			// final long written = write();
			// stop miscounting runs
			if(isRunning) return;
			long startTime = System.currentTimeMillis();
			write();
			long endTime = System.currentTimeMillis();
			configservice.getEngineContext().setSecondsConsumedByWriter(
					(double) (endTime - startTime) / 1000);
		} catch (Exception e) {
			logger.error("Exception", e);
		}

	}
   /**
    * write the sample buffer to the short term storage
    * @param buffer the sample buffer to be written
    * @throws IOException  error occurs during writing the sample buffer to the short term storage
    */
	private void write(SampleBuffer buffer) throws IOException {
		if(isRunning) return;
		isRunning=true;
		ConcurrentHashMap<String, ArchiveChannel> channelList = configservice
				.getEngineContext().getChannelList();
		String channelNname = buffer.getChannelName();
		buffer.resetSamples();
		ArrayListEventStream previousSamples = buffer.getPreviousSamples();

		try (BasicContext basicContext = new BasicContext()) {

			if (!previousSamples.isEmpty()) {
				ArchiveChannel tempChannel = channelList.get(channelNname);
				tempChannel.setlastRotateLogsEpochSeconds(System
						.currentTimeMillis() / 1000);
				tempChannel.getWriter().appendData(basicContext, channelNname,
						previousSamples);
			}
		} catch (IOException e) {
			throw (e);
		}
		finally{
			isRunning=false;
		}
		isRunning=false;
	}

	private void write() throws Exception {
		this.write(false);
	}
/**
 * write all sample buffers into short term storage
 * @throws Exception error occurs during writing the sample buffer to the short term storage
 */
	private void write(boolean force) throws Exception {
		if(isRunning) return;
		isRunning=true;
		ConcurrentHashMap<String, ArchiveChannel> channelList = configservice
				.getEngineContext().getChannelList();

		long allocatedMemory = (Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
		long presumableFreeMemory = Runtime.getRuntime().maxMemory() - allocatedMemory;
		double freeMB = presumableFreeMemory / 1.0e6;
		logger.info(String.format("Starting write cycle: %d buffers and %.2f MB free", buffers.size(), freeMB));
		int writeCount = 0;
		long t1 = System.currentTimeMillis();

        for (Entry<String, SampleBuffer> entry : buffers.entrySet()) {
            SampleBuffer buffer = entry.getValue();
            String channelNname = buffer.getChannelName();
			ArrayListEventStream samples = buffer.getCurrentSamples();
			// TODO: handle when sampling rate changes - kinda done
            int maxsize = samples.initialSizeHint; //buffer.getCapacity();
            int cursize = buffer.getQueueSize();
            int counter = skipCounter.getOrDefault(channelNname, 0);
            double ratio = ((double) cursize) / maxsize;
			if (force) {
				logger.warn(String.format("Doing a forced write for %s with %d / %d events (%.5f full)", channelNname, cursize, maxsize, ratio));
			} else {
				if (ratio < minWriteRatio) {
					if (counter > maxWriteSkips) {
						if (ratio > 0) {
							logger.info(String.format("Skip count exceeded for %s but only have %d / %d events (%.5f full) - writing anyways", channelNname, cursize, maxsize, ratio));
						}
					} else {
						//logger.debug("Skipping write for " + channelNname + String.format("because only have %d / %d events (%f full)", cursize, maxsize, ratio));
						skipCounter.put(channelNname, counter + 1);
						continue;
					}
				}
			}
			if (ratio >= 1.0) {
                logger.warn("Buffer of " + channelNname + " was found full - this means data loss occured");
            }
			skipCounter.put(channelNname, 0);

			if (!samples.isEmpty()) {
				buffer.resetSamples();
				ArrayListEventStream previousSamples = buffer.getPreviousSamples();
				try (BasicContext basicContext = new BasicContext()) {
					if (!previousSamples.isEmpty()) {
						ArchiveChannel tempChannel = channelList.get(channelNname);
						tempChannel.aboutToWriteBuffer((DBRTimeEvent) previousSamples.get(previousSamples.size() - 1));
						tempChannel.setlastRotateLogsEpochSeconds(System
								.currentTimeMillis() / 1000);
						tempChannel.getWriter().appendData(basicContext,
								channelNname, previousSamples);
					}
				} catch (IOException e) {
					throw (e);
				} finally {
					isRunning = false;
				}
				writeCount++;
			}
        }
		logger.info(String.format("Finished write cycle: %d writes in %d ms", writeCount, System.currentTimeMillis() - t1));
		isRunning=false;

	}

	/**
	 * flush out the sample buffer to the short term storage before shutting down the engine
	 * @throws Exception  error occurs during writing the sample buffer to the short term storage
	 */
	public void flushBuffer() throws Exception {
		
			write(true);
	}

}
