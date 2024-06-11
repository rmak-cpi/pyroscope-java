package io.pyroscope.javaagent;

import io.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.kubernetes.client.extended.leaderelection.LeaderElector;
import io.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.Threads;

import io.pyroscope.javaagent.api.Exporter;
import io.pyroscope.javaagent.api.Logger;
import io.pyroscope.javaagent.api.ProfilingScheduler;
import io.pyroscope.javaagent.config.Config;
import io.pyroscope.javaagent.impl.*;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class PyroscopeAgent {
    private static final Object sLock = new Object();
    private static Options sOptions = null;
    private static LeaderElectionGate sLeaderElectionGate = null;

    public static void premain(final String agentArgs,
                               final Instrumentation inst) {
        final Config config;
        try {
            config = Config.build(DefaultConfigurationProvider.INSTANCE);
            DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.DEBUG, "Config: %s", config);
        } catch (final Throwable e) {
            DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.ERROR, "Error starting profiler %s", e);
            return;
        }
        start(config);
    }

    public static void start() {
        start(new Config.Builder().build());
    }

    public static void start(Config config) {
        start(new Options.Builder(config).build());
    }

    private static class LeaderElectionGate implements AutoCloseable {
        private LeaderElector leaderElector;
        private ExecutorService leaderElectionExecutor =
            Executors.newSingleThreadExecutor(Threads.threadFactory("leader-election-executor-%d"));
        private final Options options;

        private LeaderElectionGate(Options options) throws ApiException, IOException, InvalidPathException {
            LeaseLock lock = new LeaseLock(
                getCurrentNamespace(),
                getLockName(options),
                getLockIdentity(),
                ClientBuilder.cluster().build()
            );
            leaderElector = new LeaderElector(
                new LeaderElectionConfig(
                    lock,
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(5)
                )
            );
            this.options = options;
        }

        public void run() {
            leaderElectionExecutor.submit(
                () -> leaderElector.run(
                    () -> {
                        options.logger.log(Logger.Level.INFO, "Assuming leadership.");
                        PyroscopeAgent.startInternal(options);
                    },
                    PyroscopeAgent::stopInternal
                )
            );
        }

        private static String getCurrentNamespace() throws IOException, InvalidPathException {
            return new String(
                Files.readAllBytes(Paths.get("/var/run/secrets/kubernetes.io/serviceaccount/namespace")),
                UTF_8);
        }

        private static String getLockName(Options options) {
            return Pattern.compile("[^a-z0-9\\-]").matcher(
                Pattern.compile("^[^a-z0-9]")
                    .matcher(options.config.applicationName.toLowerCase()).replaceAll("x")
                ).replaceAll("-")+"-pyroscope-leader";
        }

        private static String getLockIdentity() {
            return System.getenv().getOrDefault(
                "POD_NAME",
                UUID.randomUUID().toString().toLowerCase()
            );
        }

        @Override
        public void close() {
            if (leaderElector != null) {
                leaderElector.close();
                leaderElector = null;
            }
            if (leaderElectionExecutor != null) {
                leaderElectionExecutor.shutdown();
                leaderElectionExecutor = null;
            }
        }
    }

    static void startInternal(Options options) {
        synchronized (sLock) {
            Logger logger = options.logger;

            if (sOptions != null) {
                logger.log(Logger.Level.ERROR, "Failed to start profiling - already started");
                return;
            }
            sOptions = options;
            logger.log(Logger.Level.DEBUG, "Config: %s", options.config);
            try {
                options.scheduler.start(options.profiler);
                logger.log(Logger.Level.INFO, "Profiling started");
            } catch (final Throwable e) {
                logger.log(Logger.Level.ERROR, "Error starting profiler %s", e);
                sOptions = null;
            }
        }
    }

    private static void startWithLeaderElectionGate(Options options) {
        synchronized (sLock) {
            Logger logger = options.logger;
            try {
                if (sLeaderElectionGate != null) {
                    logger.log(Logger.Level.ERROR, "Failed to start profiling - already started");
                    return;
                }
                sLeaderElectionGate = new LeaderElectionGate(options);
                logger.log(Logger.Level.DEBUG, "Config: %s", options.config);
                sLeaderElectionGate.run();
                logger.log(Logger.Level.INFO, "Leader election started");
            } catch (final Throwable e) {
                logger.log(Logger.Level.ERROR, "Error starting leader election %s", e);
            }
        }
    }

    public static void start(Options options) {
        Logger logger = options.logger;
        if (!options.config.agentEnabled) {
            logger.log(Logger.Level.INFO, "Pyroscope agent start disabled by configuration");
            return;
        }
        if (options.config.kubernetesLeaderElectionGateEnabled) {
            startWithLeaderElectionGate(options);
        } else {
            startInternal(options);
        }
    }

    static void stopInternal() {
        synchronized (sLock) {
            if (sOptions == null) {
                DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.WARN, "Error stopping profiler: not started");
                return;
            }
            try {
                sOptions.scheduler.stop();
                sOptions.logger.log(Logger.Level.INFO, "Profiling stopped");
            } catch (Throwable e) {
                sOptions.logger.log(Logger.Level.ERROR, "Error stopping profiler %s", e);
            }

            sOptions = null;
        }
    }

    static void stopWithLeaderElectionGate() {
        synchronized (sLock) {
            Logger logger = sOptions == null ? DefaultLogger.PRECONFIG_LOGGER : sOptions.logger;
            if (sLeaderElectionGate == null) {
                return;
            }
            try {
                sLeaderElectionGate.close();
                logger.log(Logger.Level.INFO, "Leader election stopped");
            } catch (Throwable e) {
                logger.log(Logger.Level.ERROR, "Error stopping leader election %s", e);
            }
            sLeaderElectionGate = null;
        }
    }

    public static void stop() {
        stopWithLeaderElectionGate();
        stopInternal();
    }

    public static boolean isStarted() {
        synchronized (sLock) {
            return sOptions != null || sLeaderElectionGate != null;
        }
    }

    /**
     * Options allow to swap pyroscope components:
     * - io.pyroscope.javaagent.api.ProfilingScheduler
     * - org.apache.logging.log4j.Logger
     * - io.pyroscope.javaagent.api.Exporter for io.pyroscope.javaagent.impl.ContinuousProfilingScheduler
     */
    public static class Options {
        final Config config;
        final ProfilingScheduler scheduler;
        final Logger logger;
        final Profiler profiler;
        final Exporter exporter;

        private Options(Builder b) {
            this.config = b.config;
            this.profiler = b.profiler;
            this.scheduler = b.scheduler;
            this.logger = b.logger;
            this.exporter = b.exporter;
        }

        public static class Builder {
            final Config config;
            final Profiler profiler;
            Exporter exporter;
            ProfilingScheduler scheduler;
            Logger logger;

            public Builder(Config config) {
                this.config = config;
                this.profiler = new Profiler(config);
            }

            public Builder setExporter(Exporter exporter) {
                this.exporter = exporter;
                return this;
            }

            public Builder setScheduler(ProfilingScheduler scheduler) {
                this.scheduler = scheduler;
                return this;
            }

            public Builder setLogger(Logger logger) {
                this.logger = logger;
                return this;
            }

            public Options build() {
                if (logger == null) {
                    logger = new DefaultLogger(config.logLevel, System.err);
                }
                if (scheduler == null) {
                    if (exporter == null) {
                        exporter = new QueuedExporter(config, new PyroscopeExporter(config, logger), logger);
                    }
                    if (config.samplingDuration == null) {
                        scheduler = new ContinuousProfilingScheduler(config, exporter, logger);
                    } else {
                        scheduler = new SamplingProfilingScheduler(config, exporter, logger);
                    }
                }

                return new Options(this);
            }
        }

    }

}
