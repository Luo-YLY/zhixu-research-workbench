package local.research.workbench;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import local.research.workbench.project.ProjectApi;
import local.research.workbench.run.*;
import local.research.workbench.task.TaskApi;
import local.research.workbench.workflow.DemoWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class RestartRecoveryTest {
    private ConfigurableApplicationContext open(Path directory) {
        String root=directory.toAbsolutePath().toString().replace('\\','/');
        return new SpringApplicationBuilder(WorkbenchApplication.class).web(WebApplicationType.NONE).profiles("local")
                .run("--spring.datasource.url=jdbc:h2:file:"+root+"/db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                        "--workbench.data-dir="+root,"--workbench.worker.enabled=false","--spring.main.banner-mode=off");
    }
    @Test void interruptedNodesAndArchiveResumeAfterActualContextRestart() throws Exception {
        Files.createDirectories(Path.of("target"));
        Path directory=Files.createTempDirectory(Path.of("target"),"restart-"); String runId;
        try(var app=open(directory)) {
            var project=app.getBean(ProjectApi.class).create(new ProjectApi.CreateProject("重启测试",""));
            var task=app.getBean(TaskApi.class).create(new TaskApi.CreateTask(UUID.fromString(project.id()),"重启恢复","检查持久化状态"));
            runId=app.getBean(RunService.class).start(task.id(),"restart").id();
            app.getBean(DemoWorkflow.class).advance(runId);
            assertThat(app.getBean(RunStore.class).detail(runId).nodes().getFirst().status()).isEqualTo("RUNNING");
        }
        try(var app=open(directory)) {
            var workflow=app.getBean(DemoWorkflow.class);
            for(int i=0;i<6;i++) workflow.advance(runId);
            var waiting=app.getBean(RunStore.class).detail(runId);
            assertThat(waiting.status()).isEqualTo("WAITING_APPROVAL");
            app.getBean(RunService.class).decide(waiting.approval().id(),"APPROVE","重启后审批");
            workflow.advance(runId);
            assertThat(app.getBean(RunStore.class).detail(runId).nodes().getLast().status()).isEqualTo("RUNNING");
        }
        try(var app=open(directory)) {
            app.getBean(DemoWorkflow.class).advance(runId);
            app.getBean(DemoWorkflow.class).advance(runId);
            var completed=app.getBean(RunStore.class).detail(runId);
            assertThat(completed.status()).isEqualTo("COMPLETED");
            assertThat(completed.artifacts()).hasSize(1);
        }
    }
}
