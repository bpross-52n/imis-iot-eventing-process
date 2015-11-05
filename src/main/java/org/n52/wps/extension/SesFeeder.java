package org.n52.wps.extension;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import net.opengis.sos.x20.GetObservationResponseType.ObservationData;

import org.apache.xmlbeans.XmlException;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.n52.epos.engine.EposEngine;
import org.n52.epos.event.EposEvent;
import org.n52.epos.transform.TransformationException;
import org.n52.epos.transform.TransformationRepository;

/**
 * Component that periodically requests a configurable time span of
 * observations from a SOS and supplies them to the EPOS engine.
 *
 * @author Christian Autermann
 */
public class SesFeeder implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(SesFeeder.class);
    private final long samplingRate;
    private final SosClient client;
    private DateTime last;

    /**
     * Creates a new {@code SesFeeder}.
     *
     * @param client       the SOS client to request observations
     * @param samplingRate the interval to request observations for
     */
    public SesFeeder(SosClient client, Long samplingRate) {
        this.client = Objects.requireNonNull(client);
        this.samplingRate = samplingRate;
    }

    @Override
    public void run() {
        // the first timespan will be from now-samplingrate to now
        this.last = DateTime.now().minus(this.samplingRate);
        while (true && !Thread.currentThread().isInterrupted()) {
            // during is exclusive boundaries, so shift the begin
            // 1ms to the past to get every timestamp exactly one time
            DateTime begin = last.minus(1L);
            DateTime end = DateTime.now();
            this.last = end;
            try {
                Arrays.stream(this.client.getObservation(begin, end)
                        .getGetObservationResponse().getObservationDataArray())
                        .map(ObservationData::getOMObservation)
                        .forEach((net.opengis.om.x20.OMObservationType xml) -> {
                    LOG.info("pushing xml {}", xml);
                    try {
                        EposEvent event
                                = TransformationRepository.Instance.transform(xml, EposEvent.class);
                        LOG.info("created event {}", event);
                        EposEngine.getInstance().filterEvent(event);
                    } catch (TransformationException ex) {
                        LOG.error("TransformationException", ex);
                    }
                });
                try {
                    // TODO maybe use a Timer for this
                    // wait for the specified time span
                    Thread.sleep(samplingRate);
                } catch (InterruptedException ex) {
                    LOG.info("Interrupted", ex);
                    // reset the interrupted state
                    Thread.interrupted();
                    break;
                }
            } catch (IOException ex) {
                LOG.error("IOException", ex);
            } catch (XmlException ex) {
                LOG.error("XmlException", ex);
            }
        }
    }

}