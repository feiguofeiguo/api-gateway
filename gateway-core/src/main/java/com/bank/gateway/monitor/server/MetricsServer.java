package com.bank.gateway.monitor.server;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.dropwizard.DropwizardExports;
import lombok.Data;
import org.springframework.stereotype.Component;

@Data
@Component
public class MetricsServer {

    private static MetricsServer metricServer;
    private final MetricRegistry metricRegistry = new MetricRegistry();
    private Histogram responseSize;
    private Timer responsesTime;
    private Counter totalJobs;
    private Counter successJobs;
    private Histogram requestSize;
    private JmxReporter JmxReporter;
    private ConsoleReporter ConsoleReporter;

    private MetricsServer() {
        responsesTime = this.metricRegistry.timer("Time To Response");
        responseSize = this.metricRegistry.histogram("Response Size");
        requestSize = this.metricRegistry.histogram("Request Size");
        totalJobs = this.metricRegistry.counter("Total Jobs");
        successJobs=this.metricRegistry.counter("Success Jobs");
        // This the reporter for Prometheus
        CollectorRegistry.defaultRegistry.register(new DropwizardExports(metricRegistry));
        // Expose Prometheus metrics.
        PrometheusServer prometheusServer = new PrometheusServer(CollectorRegistry.defaultRegistry, 9092);
        prometheusServer.start();
        // This for JMX reporter
        JmxReporter = JmxReporter.forRegistry(metricRegistry).build();
        JmxReporter.start();
    }

}