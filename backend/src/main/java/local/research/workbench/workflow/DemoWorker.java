package local.research.workbench.workflow;

import local.research.workbench.run.RunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="workbench.worker.enabled",havingValue="true",matchIfMissing=true)
public class DemoWorker {
    private static final Logger LOG=LoggerFactory.getLogger(DemoWorker.class);
    private final RunStore store;
    private final DemoWorkflow workflow;
    public DemoWorker(RunStore store, DemoWorkflow workflow) { this.store=store; this.workflow=workflow; }
    @Scheduled(fixedDelayString="${workbench.worker.interval-ms:500}",initialDelay=1000)
    public void tick() {
        for(String id:store.runnable()) {
            try { workflow.advance(id); }
            catch(Exception e) {
                LOG.error("DEMO run {} failed",id,e);
                try { workflow.fail(id); } catch(Exception failure) { LOG.error("Cannot record run failure {}",id,failure); }
            }
        }
    }
}
