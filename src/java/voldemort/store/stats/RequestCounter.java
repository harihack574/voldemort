/*
 * Copyright 2012 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.store.stats;


import org.tehuti.Metric;
import org.tehuti.metrics.MetricConfig;
import org.tehuti.metrics.Metrics;
import org.tehuti.metrics.Sensor;
import org.tehuti.metrics.stats.*;
import org.apache.log4j.Logger;

import voldemort.utils.SystemTime;
import voldemort.utils.Time;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A thread-safe request counter that calculates throughput for a specified
 * duration of time.
 * 
 * 
 */
public class RequestCounter {

    private final Time time;

    // Sensors
    private Sensor timeSensor, emptyResponseKeysSensor, valueBytesSensor, keyBytesSensor, getAllKeysCountSensor;

    // Metrics
    private Metric
            // Averages
            latencyAverage, valueBytesAverage, keyBytesAverage,
            // Percentiles
            latency10thPercentile, latency50thPercentile, latency95thPercentile, latency99thPercentile,
            // Maximums
            latencyMax, valueBytesMax, keyBytesMax, getAllKeysCountMax,
            // Sampled Totals
            getAllKeysCountSampledTotal,
            // Sampled Counts
            requestSampledCount, emptyResponseKeysSampledCount,
            // All-time Count
            requestAllTimeCount,
            // Rates
            requestThroughput, requestThroughputInBytes;

    private static Metrics metricsRepository;

    private static final Logger logger = Logger.getLogger(RequestCounter.class.getName());

    public RequestCounter(String name, long durationMs) {
        this(name, durationMs, SystemTime.INSTANCE, false, (RequestCounter[]) null);
    }

    /**
     * @param durationMs specifies for how long you want to maintain this
     *        counter (in milliseconds).
     */
    public RequestCounter(String name, long durationMs, RequestCounter... parents) {
        this(name, durationMs, SystemTime.INSTANCE, false, parents);
    }

    public RequestCounter(String name, long durationMs, boolean useHistogram) {
        this(name, durationMs, SystemTime.INSTANCE, useHistogram, (RequestCounter[]) null);
    }

    /**
     * @param durationMs specifies for how long you want to maintain this
     *        counter (in milliseconds). useHistogram indicates that this
     *        counter should also use a histogram.
     */
    public RequestCounter(String name, long durationMs, boolean useHistogram, RequestCounter... parents) {
        this(name, durationMs, SystemTime.INSTANCE, useHistogram, parents);
    }

    RequestCounter(String name, long durationMs, Time time) {
        this(name, durationMs, time, false, (RequestCounter[]) null);
    }

    /**
     * For testing request expiration via an injected time provider
     */
    RequestCounter(String name, long durationMs, Time time, RequestCounter... parents) {
        this(name, durationMs, time, false, parents);
    }

    RequestCounter(String name, long durationMs, Time time, boolean useHistogram, RequestCounter... parents) {

        this.time = time;

        this.metricsRepository = new Metrics(time);

        // Initialize parent sensors arrays...
        int amountOfParentSensors = 0;
        if (parents != null) {
            amountOfParentSensors = parents.length;
        }
        Sensor[] timeParentSensors = null;
        Sensor[] emptyResponseKeysParentSensors = null;
        Sensor[] getAllKeysCountParentSensors = null;
        Sensor[] valueBytesParentSensors = new Sensor[amountOfParentSensors + 1];
        Sensor[] keyBytesParentSensors = new Sensor[amountOfParentSensors + 1];

        if (parents != null && parents.length > 0) {
            timeParentSensors = new Sensor[parents.length];
            emptyResponseKeysParentSensors = new Sensor[parents.length];
            getAllKeysCountParentSensors = new Sensor[parents.length];
            for (int i = 0; i < parents.length; i++) {
                timeParentSensors[i] = parents[i].timeSensor;
                emptyResponseKeysParentSensors[i] = parents[i].emptyResponseKeysSensor;
                getAllKeysCountParentSensors[i] = parents[i].getAllKeysCountSensor;
                valueBytesParentSensors[i] = parents[i].valueBytesSensor;
                keyBytesParentSensors[i] = parents[i].keyBytesSensor;
            }
        }

        // Initialize MetricConfig
        MetricConfig metricConfig = new MetricConfig().timeWindow(durationMs, TimeUnit.MILLISECONDS);

        // Time Sensor

        String timeSensorName = name + ".time";
        this.timeSensor =
                metricsRepository.sensor(timeSensorName, metricConfig, timeParentSensors);
        if (useHistogram) {
            Percentiles timePercentiles = new Percentiles(40000, 10000, Percentiles.BucketSizing.LINEAR,
                    new Percentile(timeSensorName + ".10thPercentile", 0.10),
                    new Percentile(timeSensorName + ".50thPercentile", 0.50),
                    new Percentile(timeSensorName + ".95thPercentile", 0.95),
                    new Percentile(timeSensorName + ".99thPercentile", 0.99));
            Map<String, Metric> percentiles = this.timeSensor.add(timePercentiles);
            this.latency10thPercentile = percentiles.get(timeSensorName + ".10thPercentile");
            this.latency50thPercentile = percentiles.get(timeSensorName + ".50thPercentile");
            this.latency95thPercentile = percentiles.get(timeSensorName + ".95thPercentile");
            this.latency99thPercentile = percentiles.get(timeSensorName + ".99thPercentile");
        }
        this.latencyMax = this.timeSensor.add(timeSensorName + ".max", new Max());
        this.latencyAverage = this.timeSensor.add(timeSensorName + ".avg", new Avg());
        // Sampled count, all-time count and throughput rate, piggy-backing off of the Time Sensor
        this.requestSampledCount = this.timeSensor.add(name + ".sampled-count", new SampledCount());
        this.requestAllTimeCount = this.timeSensor.add(name + ".count", new Count());
        this.requestThroughput = this.timeSensor.add(name + ".throughput", new OccurrenceRate());

        // Empty Reponse Keys Sensor

        String emptyResponseKeysSensorName = name + ".empty-response-keys";
        this.emptyResponseKeysSensor =
                metricsRepository.sensor(emptyResponseKeysSensorName, metricConfig, emptyResponseKeysParentSensors);
        this.emptyResponseKeysSampledCount =
                this.emptyResponseKeysSensor.add(emptyResponseKeysSensorName + ".sampled-count", new SampledCount());

        // Key and Value Bytes Sensor

        String keyAndValueBytesSensorName= name + ".key-and-value-bytes";
        Sensor keyAndValueBytesSensor = metricsRepository.sensor(keyAndValueBytesSensorName, metricConfig);
        this.requestThroughputInBytes =
                keyAndValueBytesSensor.add(keyAndValueBytesSensorName + ".bytes-throughput", new Rate());
        valueBytesParentSensors[amountOfParentSensors] = keyAndValueBytesSensor;
        keyBytesParentSensors[amountOfParentSensors] = keyAndValueBytesSensor;

        // Value Bytes Sensor

        String valueBytesSensorName = name + ".value-bytes";
        this.valueBytesSensor = metricsRepository.sensor(valueBytesSensorName, metricConfig, valueBytesParentSensors);
        this.valueBytesMax = this.valueBytesSensor.add(valueBytesSensorName + ".max", new Max());
        this.valueBytesAverage = this.valueBytesSensor.add(valueBytesSensorName + ".avg", new Avg());

        // Key Bytes Sensor

        String keyBytesSensorName = name + ".key-bytes";
        this.keyBytesSensor = metricsRepository.sensor(keyBytesSensorName, metricConfig, keyBytesParentSensors);
        this.keyBytesMax = this.keyBytesSensor.add(keyBytesSensorName + ".max", new Max());
        this.keyBytesAverage = this.keyBytesSensor.add(keyBytesSensorName + ".avg", new Avg());

        // Get All Keys Count Sensor

        String getAllKeysCountSensorName = name + ".get-all-keys-count";
        this.getAllKeysCountSensor =
                metricsRepository.sensor(getAllKeysCountSensorName, metricConfig, getAllKeysCountParentSensors);
        this.getAllKeysCountSampledTotal =
                this.getAllKeysCountSensor.add(getAllKeysCountSensorName + ".sampled-total", new SampledTotal());
        this.getAllKeysCountMax = this.getAllKeysCountSensor.add(getAllKeysCountSensorName + ".max", new Max());
    }

    public long getCount() {
        return (long) requestSampledCount.value();
    }

    public long getTotalCount() {
        return (long) requestAllTimeCount.value();
    }

    public float getThroughput() {
        // TODO: Check for overflow?
        return new Float(requestThroughput.value());
    }

    public float getThroughputInBytes() {
        // TODO: Check for overflow?
        return new Float(requestThroughputInBytes.value());
    }

    public String getDisplayThroughput() {
        return String.format("%.2f", getThroughput());
    }

    public double getAverageTimeInMs() {
        return latencyAverage.value();
    }

    public String getDisplayAverageTimeInMs() {
        return String.format("%.4f", getAverageTimeInMs());
    }

    // TODO: Make sure this is really useless
//    public long getDuration() {
//        return 0;
////        return durationMs;
//    }

    public long getMaxLatencyInMs() {
        return (long) latencyMax.value();
    }

    /**
     * @param timeNS time of operation, in nanoseconds
     */
    public void addRequest(long timeNS) {
        addRequest(timeNS, 0, 0, 0, 0);
    }

    /**
     * Detailed request to track additional data about PUT, GET and GET_ALL
     *
     * @param timeNS The time in nanoseconds that the operation took to complete
     * @param numEmptyResponses For GET and GET_ALL, how many keys were no values found
     * @param valueBytes Total number of bytes across all versions of values' bytes
     * @param keyBytes Total number of bytes in the keys
     * @param getAllAggregatedCount Total number of keys returned for getAll calls
     */
    public void addRequest(long timeNS,
                           long numEmptyResponses,
                           long valueBytes,
                           long keyBytes,
                           long getAllAggregatedCount) {
        // timing instrumentation (trace only)
        long startTimeNs = 0;
        if(logger.isTraceEnabled()) {
            startTimeNs = System.nanoTime();
        }

        long currentTime = time.getMilliseconds();

        timeSensor.record((double) timeNS / Time.NS_PER_MS, currentTime);
        emptyResponseKeysSensor.record(numEmptyResponses, currentTime);
        valueBytesSensor.record(valueBytes, currentTime);
        keyBytesSensor.record(keyBytes, currentTime);
        getAllKeysCountSensor.record(getAllAggregatedCount, currentTime);

        // timing instrumentation (trace only)
        if(logger.isTraceEnabled()) {
            logger.trace("addRequest (histogram.insert and accumulator update) took "
                         + (System.nanoTime() - startTimeNs) + " ns.");
        }
    }

    /**
     * Return the number of requests that have returned returned no value for
     * the requested key. Tracked only for GET.
     */
    public long getNumEmptyResponses() {
        return (long) emptyResponseKeysSampledCount.value();
    }

    /**
     * Return the size of the largest response or request in bytes returned.
     * Tracked only for GET, GET_ALL and PUT.
     */
    public long getMaxValueSizeInBytes() {
        return (long) valueBytesMax.value();
    }

    /**
     * Return the size of the largest response or request in bytes returned.
     */
    public long getMaxKeySizeInBytes() {
        return (long) keyBytesMax.value();
    }

    /**
     * Return the average size of all the versioned values returned. Tracked
     * only for GET, GET_ALL and PUT.
     */
    public double getAverageValueSizeInBytes() {
        return valueBytesAverage.value();
    }

    /**
     * Return the average size of all the keys. Tracked for all operations.
     */
    public double getAverageKeySizeInBytes() {
        return keyBytesAverage.value();
    }

    /**
     * Return the aggregated number of keys returned across all getAll calls,
     * taking into account multiple values returned per call.
     */
    public long getGetAllAggregatedCount() {
        return (long) getAllKeysCountSampledTotal.value();
    }

    /**
     * Return the maximum number of keys returned across all getAll calls.
     */
    public long getGetAllMaxCount() {
        return (long) getAllKeysCountMax.value();
    }
    
    public double getQ10LatencyMs() {
        return latency10thPercentile.value();
    }
    
    public double getQ50LatencyMs() {
        return latency50thPercentile.value();
    }
    
    public double getQ95LatencyMs() {
        return latency95thPercentile.value();
    }
    
    public double getQ99LatencyMs() {
        return latency99thPercentile.value();
    }
}
