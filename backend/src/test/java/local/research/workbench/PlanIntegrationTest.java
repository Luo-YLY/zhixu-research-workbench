package local.research.workbench;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import local.research.workbench.plan.PlanApi;
import local.research.workbench.plan.PlanService;
import local.research.workbench.project.ProjectApi;
import local.research.workbench.task.TaskApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:plan-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workbench.worker.enabled=false","workbench.data-dir=target/test-plan-artifacts"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class PlanIntegrationTest {
    @Autowired ProjectApi projects;
    @Autowired TaskApi tasks;
    @Autowired PlanService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private String task() {
        var project=projects.create(new ProjectApi.CreateProject("周期研究","测试计划"));
        return tasks.create(new TaskApi.CreateTask(UUID.fromString(project.id()),"复核数据口径","每周复核")).id();
    }

    @Test void weeklyScheduleGeneratesOneDurableItemAndPauseStopsFutureDays() throws Exception {
        String taskId=task(); LocalDate today=service.today();
        var rule=service.createSchedule(new PlanApi.CreateSchedule(UUID.fromString(taskId),"WEEKLY",
                today.getDayOfWeek().getValue(),LocalTime.of(9,30),today));
        assertThat(service.plans(today)).filteredOn(item->rule.id().equals(item.scheduleId())).hasSize(1);
        service.plans(today); service.materializeToday();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_plan WHERE schedule_id=?",Long.class,rule.id())).isEqualTo(1);
        var item=service.plans(today).stream().filter(p->rule.id().equals(p.scheduleId())).findFirst().orElseThrow();
        assertThat(service.setDone(item.id(),true).status()).isEqualTo("DONE");
        assertThat(service.plans(today)).filteredOn(p->p.id().equals(item.id())).first().extracting(PlanApi.Item::completedAt).isNotNull();
        service.setActive(rule.id(),false);
        assertThat(service.plans(today.plusWeeks(1))).noneMatch(p->rule.id().equals(p.scheduleId()));
        assertThat(service.plans(today).stream().filter(p->p.id().equals(item.id())).findFirst().orElseThrow().status()).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run WHERE task_id=?",Long.class,taskId)).isZero();
        mvc.perform(get("/api/plans").param("date",today.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='"+item.id()+"')].status").value("DONE"));
    }

    @Test void manualItemCanBeCompletedAndReopenedThroughApi() throws Exception {
        var item=service.createPlan(new PlanApi.CreateItem(" 阅读方法部分 ",service.today(),null,null));
        assertThat(item.title()).isEqualTo("阅读方法部分");
        mvc.perform(post("/api/plans/"+item.id()+"/complete")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        mvc.perform(post("/api/plans/"+item.id()+"/reopen")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("TODO"));
        assertThat(service.plans(service.today())).anyMatch(p->p.id().equals(item.id()) && p.completedAt()==null);
    }
}
