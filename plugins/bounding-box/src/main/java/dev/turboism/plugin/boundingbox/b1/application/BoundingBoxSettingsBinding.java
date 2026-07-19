package dev.turboism.plugin.boundingbox.b1.application;

import dev.turboism.plugin.boundingbox.b1.domain.BoundingBoxFeatureSettings;
import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigErrorCode;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigRegistrationError;
import dev.turboism.sdk.config.ConfigRegistrationException;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class BoundingBoxSettingsBinding {
    public static final String CONFIG_ID="bounding-box.features";
    public static final String CONFIG_PATH="bounding-box/features.cfg";
    private static final ConfigKey<Boolean> OVERLAY=new ConfigKey<>(CONFIG_ID,"overlayButtonsEnabled",true,ConfigCodecs.booleanValue());
    private static final ConfigKey<Boolean> WORKSPACE=new ConfigKey<>(CONFIG_ID,"workspaceButtonsEnabled",true,ConfigCodecs.booleanValue());
    private static final ConfigKey<Boolean> SUPPRESSED=new ConfigKey<>(CONFIG_ID,"mirrorAndShrinkSuppressed",false,ConfigCodecs.booleanValue());
    private static final ConfigSchema SCHEMA=new ConfigSchema(CONFIG_ID,CONFIG_PATH,1,List.of(OVERLAY,WORKSPACE,SUPPRESSED));
    private PluginConfigRegistry registry; private BoundingBoxFeatureSettings confirmed=BoundingBoxFeatureSettings.defaults(); private long revision; private long epoch; private boolean initialized; private boolean enabled;

    public CompletionStage<ConfigBindingResult> init(PluginConfigRegistry value){registry=Objects.requireNonNull(value,"value");try{return registry.registerSchema(SCHEMA,List.of()).handle((ignored,failure)->{if(failure==null){initialized=true;return ConfigBindingResult.APPLIED;}return registration(unwrap(failure));});}catch(RuntimeException failure){return completed(registration(failure));}}
    public CompletionStage<ConfigBindingResult> enable(){if(!initialized||registry==null)return completed(ConfigBindingResult.RUNTIME_UNAVAILABLE);enabled=true;long active=++epoch;return registry.read(OVERLAY).thenCombine(registry.read(WORKSPACE),Pair::new).thenCombine(registry.read(SUPPRESSED),Triple::new).handle((reads,failure)->{if(!enabled||epoch!=active)return ConfigBindingResult.DISABLED;if(failure!=null)return ConfigBindingResult.RUNTIME_UNAVAILABLE;if(reads.first().error().isPresent()||reads.second().error().isPresent()||reads.third().error().isPresent())return ConfigBindingResult.RUNTIME_UNAVAILABLE;long r=reads.first().value().revision();if(reads.second().value().revision()!=r||reads.third().value().revision()!=r)return ConfigBindingResult.REVISION_CONFLICT;confirmed=new BoundingBoxFeatureSettings(reads.first().value().value(),reads.second().value().value(),reads.third().value().value());revision=r;return ConfigBindingResult.APPLIED;});}
    public CompletionStage<ConfigBindingResult> update(BoundingBoxFeatureSettings value){Objects.requireNonNull(value,"value");if(!enabled||registry==null)return completed(ConfigBindingResult.DISABLED);if(value.equals(confirmed))return completed(ConfigBindingResult.UNCHANGED);long active=epoch;return write(OVERLAY,value.overlayButtonsEnabled(),revision).thenCompose(a->next(a,WORKSPACE,value.workspaceButtonsEnabled(),active)).thenCompose(b->next(b,SUPPRESSED,value.mirrorAndShrinkSuppressed(),active)).thenApply(last->{if(!enabled||epoch!=active)return ConfigBindingResult.DISABLED;if(last.result()!=null)return last.wroteAny()?ConfigBindingResult.PARTIAL_PERSISTENCE:last.result();revision=last.revision();confirmed=value;return ConfigBindingResult.APPLIED;});}
    public void disable(){enabled=false;epoch++;} public void shutdown(){disable();initialized=false;registry=null;} public BoundingBoxFeatureSettings confirmed(){return confirmed;}
    private <T> CompletionStage<Step> write(ConfigKey<T> key,T value,long expected){return registry.write(key,value,expected).handle((result,failure)->failure==null?Step.from(result,false):new Step(ConfigBindingResult.RUNTIME_UNAVAILABLE,expected,false));}
    private <T> CompletionStage<Step> next(Step prior,ConfigKey<T> key,T value,long active){if(!enabled||epoch!=active)return completedStep(new Step(ConfigBindingResult.DISABLED,prior.revision(),prior.wroteAny()));if(prior.result()!=null)return completedStep(prior);return write(key,value,prior.revision()).thenApply(current->new Step(current.result(),current.revision(),prior.wroteAny()||current.wroteAny()));}
    private static ConfigBindingResult registration(Throwable failure){return failure instanceof ConfigRegistrationException r&&r.error()==ConfigRegistrationError.PERMISSION_DENIED?ConfigBindingResult.PERMISSION_DENIED:ConfigBindingResult.RUNTIME_UNAVAILABLE;}
    private static ConfigBindingResult map(ConfigErrorCode code){return switch(code){case REVISION_CONFLICT->ConfigBindingResult.REVISION_CONFLICT;case PERMISSION_DENIED->ConfigBindingResult.PERMISSION_DENIED;case INVALID_VALUE->ConfigBindingResult.INVALID_VALUE;default->ConfigBindingResult.RUNTIME_UNAVAILABLE;};}
    private static Throwable unwrap(Throwable value){return value.getCause()==null?value:value.getCause();} private static CompletionStage<ConfigBindingResult> completed(ConfigBindingResult value){return java.util.concurrent.CompletableFuture.completedStage(value);} private static CompletionStage<Step> completedStep(Step value){return java.util.concurrent.CompletableFuture.completedStage(value);}
    private record Pair(dev.turboism.sdk.config.ConfigReadResult<Boolean> first,dev.turboism.sdk.config.ConfigReadResult<Boolean> second){} private record Triple(Pair pair,dev.turboism.sdk.config.ConfigReadResult<Boolean> third){dev.turboism.sdk.config.ConfigReadResult<Boolean> first(){return pair.first();}dev.turboism.sdk.config.ConfigReadResult<Boolean> second(){return pair.second();}}
    private record Step(ConfigBindingResult result,long revision,boolean wroteAny){static Step from(ConfigWriteResult value,boolean previous){return value.written()?new Step(null,value.revision(),true):new Step(map(value.error().orElseThrow().code()),value.revision(),previous);}}
}
