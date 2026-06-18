package ac.grim.grimac.ml;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.event.events.GrimJoinEvent;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.manager.init.stop.StoppableInitable;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.common.ConfigReloadObserver;
import lombok.Getter;
import ac.grim.grimac.platform.api.scheduler.TaskHandle;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
public final class MlManager implements StartableInitable, StoppableInitable, ConfigReloadObserver {

    private final MlConfig config = new MlConfig();
    private final LegitTrustManager trustManager = new LegitTrustManager(config);
    private final ServerMetricsSampler sampler = new ServerMetricsSampler();
    private final MlEventStore eventStore = new MlEventStore();

    private MlModelManager modelManager;
    private AdaptiveThresholdEngine engine;
    private MlFlagListener flagListener;

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
        modelManager = new MlModelManager(config, dataFolder.resolve("ml").resolve("model.ser"));
        modelManager.loadModel();
        engine = new AdaptiveThresholdEngine(config, modelManager, sampler);
        flagListener = new MlFlagListener(config, trustManager, sampler, eventStore, modelManager, engine, this::queueRetrain);
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
        eventStore.shutdown();
    }

    public void onFlag(Check check, @Nullable String verboseSnapshot) {
        if (flagListener != null) flagListener.onFlag(check, verboseSnapshot);
    }

    public MlModelManager.TrainResult trainNow() {
        List<MlEventStore.TrainingSample> samples = eventStore.loadTrainingSamples();
        MlModelManager.TrainResult result = modelManager.train(samples);
        if (result.success()) engine.invalidateCache();
        return result;
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
                trainNow();
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
