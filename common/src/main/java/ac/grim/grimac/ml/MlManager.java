package ac.grim.grimac.ml;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.events.GrimJoinEvent;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.manager.init.stop.StoppableInitable;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.common.ConfigReloadObserver;
import lombok.Getter;
import ac.grim.grimac.platform.api.scheduler.TaskHandle;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
public final class MlManager implements StartableInitable, StoppableInitable, ConfigReloadObserver {

    private final MlConfig config = new MlConfig();
    private final LegitTrustManager trustManager = new LegitTrustManager(config);
    private final ServerMetricsSampler sampler = new ServerMetricsSampler();
    private final MlEventStore eventStore = new MlEventStore();
    private final MlTrainingSessionStore trainingSessionStore = new MlTrainingSessionStore();
    private final MlBaselineSampler baselineSampler = new MlBaselineSampler();

    private MlModelManager modelManager;
    private AdaptiveThresholdEngine engine;
    private MlObservationLogger observationLogger;

    private @Nullable TaskHandle metricsTask;
    private @Nullable TaskHandle retrainTask;
    private final AtomicBoolean retrainQueued = new AtomicBoolean(false);
    private boolean running;

    @Override
    public void onReload(ConfigManager newConfig) {
        config.reload(newConfig);
        if (engine != null) engine.invalidateCache();
        if (running) restartSchedulers();
    }

    public void load(ConfigManager configManager) {
        config.reload(configManager);
        Path dataFolder = GrimAPI.INSTANCE.getGrimPlugin().getDataFolder().toPath();
        trustManager.init(dataFolder);
        eventStore.init(dataFolder);
        trainingSessionStore.init(dataFolder);
        modelManager = new MlModelManager(config, dataFolder.resolve("ml").resolve("model.ser"));
        modelManager.loadModel();
        engine = new AdaptiveThresholdEngine(config, modelManager, sampler);
        observationLogger = new MlObservationLogger(
                config,
                trustManager,
                sampler,
                eventStore,
                trainingSessionStore,
                baselineSampler,
                engine,
                this::queueRetrain
        );
    }

    @Override
    public void start() {
        running = true;
        GrimAPI.INSTANCE.getEventBus().get(GrimJoinEvent.class).onJoin(GrimAPI.INSTANCE.getGrimPlugin(), trustManager::onJoin);
        restartSchedulers();
        queueRetrain();
        eventStore.purgeOlderThanDays(config.getEventRetentionDays());
    }

    @Override
    public void stop() {
        running = false;
        cancelTask(metricsTask);
        cancelTask(retrainTask);
        trainingSessionStore.shutdown();
        eventStore.shutdown();
    }

    public void onFlag(Check check, @Nullable String verboseSnapshot) {
        if (observationLogger != null) {
            observationLogger.logFlag(check, verboseSnapshot, Double.NaN, Double.NaN);
        }
    }

    public void onPass(Check check, String activity, double measuredValue, double configThreshold) {
        if (observationLogger != null) {
            observationLogger.logPass(check, activity, measuredValue, configThreshold);
        }
    }

    public void onBaseline(Check check, double measuredValue, double configThreshold, String activity) {
        onPass(check, activity, measuredValue, configThreshold);
    }

    public void onPlayerActivity(GrimPlayer player, String activity) {
        if (observationLogger != null) {
            observationLogger.logActivity(player, activity);
        }
    }

    public MlModelManager.TrainResult trainNow() {
        List<MlEventStore.TrainingSample> samples = resolveTrainingSamplesSync();
        MlModelManager.TrainResult result = modelManager.train(samples);
        if (result.success()) engine.invalidateCache();
        return result;
    }

    public CompletableFuture<MlModelManager.TrainResult> trainNowAsync() {
        return trainingSessionStore.loadTrainingSamplesAsync(config).thenApply(sessionSamples -> {
            List<MlEventStore.TrainingSample> samples = !sessionSamples.isEmpty()
                    ? sessionSamples
                    : eventStore.loadTrainingSamples(config);
            MlModelManager.TrainResult result = modelManager.train(samples);
            if (result.success()) engine.invalidateCache();
            return result;
        });
    }

    public CompletableFuture<MlTrainingSessionStore.StartResult> startTrainingAsync() {
        baselineSampler.clear();
        return trainingSessionStore.startSessionAsync();
    }

    public CompletableFuture<MlModelManager.TrainResult> stopTrainingAsync() {
        return trainingSessionStore.stopSessionAsync(config).thenApply(stopResult -> {
            if (!stopResult.success()) {
                return new MlModelManager.TrainResult(false, stopResult.sampleCount(), stopResult.message());
            }
            MlModelManager.TrainResult result = modelManager.train(stopResult.samples());
            if (result.success()) engine.invalidateCache();
            return result;
        });
    }

    public Map<String, Integer> trainingActivityCounts() {
        return trainingSessionStore.countByActivity();
    }

    private List<MlEventStore.TrainingSample> resolveTrainingSamplesSync() {
        List<MlEventStore.TrainingSample> sessionSamples = trainingSessionStore.loadTrainingSamplesAsync(config)
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
        if (!sessionSamples.isEmpty()) {
            return sessionSamples;
        }
        return eventStore.loadTrainingSamples(config);
    }

    public void resetModel() {
        modelManager.reset();
        eventStore.clearAll();
        engine.invalidateCache();
    }

    private void restartSchedulers() {
        cancelTask(metricsTask);
        cancelTask(retrainTask);
        if (!config.isEnabled()) return;

        metricsTask = GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runAtFixedRate(
                GrimAPI.INSTANCE.getGrimPlugin(),
                () -> sampler.sampleIfDue(config),
                config.getServerMetricsIntervalMs(),
                config.getServerMetricsIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        if (config.getRetrainIntervalMinutes() > 0) {
            long intervalTicks = config.getRetrainIntervalMinutes() * 60L * 20L;
            retrainTask = GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runAtFixedRate(
                    GrimAPI.INSTANCE.getGrimPlugin(),
                    this::queueRetrain,
                    intervalTicks,
                    intervalTicks
            );
        }
    }

    private void queueRetrain() {
        if (!config.isEnabled() || !retrainQueued.compareAndSet(false, true)) return;
        GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(GrimAPI.INSTANCE.getGrimPlugin(), () -> {
            try {
                trainNowAsync().join();
            } catch (Exception e) {
                LogUtil.warn("ml retrain failed: " + e.getMessage());
            } finally {
                retrainQueued.set(false);
            }
        });
    }

    private static void cancelTask(@Nullable TaskHandle task) {
        if (task != null) task.cancel();
    }
}
