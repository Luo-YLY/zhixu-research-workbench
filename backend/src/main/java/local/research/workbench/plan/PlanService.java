package local.research.workbench.plan;

import static local.research.workbench.shared.SqlSupport.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import local.research.workbench.audit.AuditLog;
import local.research.workbench.shared.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanService {
    public static final ZoneId PLANNING_ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final PlanStore store;
    private final AuditLog audit;
    public PlanService(JdbcTemplate jdbc,PlanStore store,AuditLog audit) { this.jdbc=jdbc;this.store=store;this.audit=audit; }
    public List<PlanApi.Schedule> schedules() { return store.schedules(); }
    public LocalDate today() { return LocalDate.now(PLANNING_ZONE); }

    @Transactional
    public PlanApi.Schedule createSchedule(PlanApi.CreateSchedule request) {
        String frequency=request.frequency().strip().toUpperCase();
        if (!List.of("DAILY","WEEKLY").contains(frequency)) throw new ApiException(400,"INVALID_FREQUENCY","周期只支持每天或每周");
        if (frequency.equals("DAILY") && request.weekday()!=null || frequency.equals("WEEKLY") &&
                (request.weekday()==null || request.weekday()<1 || request.weekday()>7))
            throw new ApiException(400,"INVALID_WEEKDAY","每周计划须指定周一至周日；每日计划无需星期");
        if (request.startDate().isBefore(today().minusDays(1)) || request.startDate().isAfter(today().plusYears(1)))
            throw new ApiException(400,"INVALID_START_DATE","开始日期须在昨天至未来一年之间");
        String taskId=request.taskId().toString();
        if (jdbc.queryForObject("SELECT COUNT(*) FROM research_task WHERE id=?",Long.class,taskId)==0)
            throw ApiException.notFound("任务");
        String id=id();
        jdbc.update("INSERT INTO recurring_schedule(id,task_id,frequency,weekday,planned_time,start_date,active,created_at) VALUES (?,?,?,?,?,?,?,?)",
                id,taskId,frequency,request.weekday(),request.plannedTime(),request.startDate(),true,now());
        audit.record("SCHEDULE_CREATED",id,"task="+taskId+"; frequency="+frequency);
        return store.schedule(id);
    }

    @Transactional
    public PlanApi.Schedule setActive(String id,boolean active) {
        if (jdbc.update("UPDATE recurring_schedule SET active=? WHERE id=?",active,id)==0) throw ApiException.notFound("周期计划");
        audit.record(active?"SCHEDULE_RESUMED":"SCHEDULE_PAUSED",id,"");
        return store.schedule(id);
    }

    @Transactional
    public List<PlanApi.Item> plans(LocalDate date) {
        LocalDate selected=date==null?today():date;
        if (selected.isBefore(today().minusYears(1)) || selected.isAfter(today().plusYears(1)))
            throw new ApiException(400,"INVALID_PLAN_DATE","只能查看前后一年内的计划");
        materialize(selected);
        return store.plans(selected);
    }

    @Transactional
    public PlanApi.Item createPlan(PlanApi.CreateItem request) {
        if (request.date().isBefore(today().minusYears(1)) || request.date().isAfter(today().plusYears(1)))
            throw new ApiException(400,"INVALID_PLAN_DATE","计划日期须在前后一年内");
        String taskId=request.taskId()==null?null:request.taskId().toString();
        if (taskId!=null && jdbc.queryForObject("SELECT COUNT(*) FROM research_task WHERE id=?",Long.class,taskId)==0)
            throw ApiException.notFound("任务");
        String title=request.title().strip();
        if (title.isEmpty()) throw new ApiException(400,"INVALID_TITLE","请填写计划内容");
        String id=id();
        jdbc.update("INSERT INTO daily_plan(id,plan_date,planned_time,task_id,schedule_id,title,status,created_at) VALUES (?,?,?,?,?,?,?,?)",
                id,request.date(),request.plannedTime(),taskId,null,title,"TODO",now());
        audit.record("PLAN_CREATED",id,"date="+request.date());
        return store.plan(id);
    }

    @Transactional
    public PlanApi.Item setDone(String id,boolean done) {
        var existing=store.lockPlan(id);
        if (existing.status().equals(done?"DONE":"TODO")) return existing;
        jdbc.update("UPDATE daily_plan SET status=?,completed_at=? WHERE id=?",done?"DONE":"TODO",done?now():null,id);
        audit.record(done?"PLAN_COMPLETED":"PLAN_REOPENED",id,"");
        return store.plan(id);
    }

    /** Materialize the selected day's due work exactly once; no DEMO run is started automatically. */
    void materialize(LocalDate date) {
        for (var schedule:store.schedules()) {
            if (!schedule.active() || date.isBefore(schedule.startDate()) ||
                    schedule.frequency().equals("WEEKLY") && date.getDayOfWeek().getValue()!=schedule.weekday()) continue;
            if (!store.lockScheduleIsActive(schedule.id())) continue;
            if (!store.planExists(schedule.id(),date)) {
                jdbc.update("INSERT INTO daily_plan(id,plan_date,planned_time,task_id,schedule_id,title,status,created_at) VALUES (?,?,?,?,?,?,?,?)",
                        id(),date,schedule.plannedTime(),schedule.taskId(),schedule.id(),schedule.taskTitle(),"TODO",now());
            }
        }
    }

    @Scheduled(cron="0 0 0 * * *",zone="Asia/Shanghai")
    @Transactional
    public void materializeToday() { materialize(today()); }
}
