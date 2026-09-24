package local.research.workbench.plan;

import static local.research.workbench.shared.SqlSupport.time;
import java.time.LocalDate;
import java.util.List;
import local.research.workbench.shared.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PlanStore {
    private static final RowMapper<PlanApi.Schedule> SCHEDULE=(r,n)->new PlanApi.Schedule(
            r.getString("id"),r.getString("task_id"),r.getString("task_title"),r.getString("frequency"),
            (Integer)r.getObject("weekday"),r.getTime("planned_time").toLocalTime(),
            r.getDate("start_date").toLocalDate(),r.getBoolean("active"),time(r,"created_at"));
    private static final RowMapper<PlanApi.Item> PLAN=(r,n)->new PlanApi.Item(
            r.getString("id"),r.getDate("plan_date").toLocalDate(),
            r.getTime("planned_time")==null?null:r.getTime("planned_time").toLocalTime(),
            r.getString("task_id"),r.getString("schedule_id"),r.getString("title"),r.getString("status"),
            time(r,"created_at"),time(r,"completed_at"));
    private final JdbcTemplate jdbc;
    public PlanStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<PlanApi.Schedule> schedules() {
        return jdbc.query("SELECT s.*,t.title AS task_title FROM recurring_schedule s JOIN research_task t ON t.id=s.task_id ORDER BY s.created_at DESC,s.id",SCHEDULE);
    }
    public PlanApi.Schedule schedule(String id) {
        var rows=jdbc.query("SELECT s.*,t.title AS task_title FROM recurring_schedule s JOIN research_task t ON t.id=s.task_id WHERE s.id=?",SCHEDULE,id);
        if(rows.isEmpty()) throw ApiException.notFound("周期计划");
        return rows.getFirst();
    }
    public boolean lockScheduleIsActive(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT active FROM recurring_schedule WHERE id=? FOR UPDATE",Boolean.class,id));
    }
    public boolean planExists(String scheduleId,LocalDate date) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM daily_plan WHERE schedule_id=? AND plan_date=?",Long.class,scheduleId,date)>0;
    }
    public List<PlanApi.Item> plans(LocalDate date) {
        return jdbc.query("SELECT * FROM daily_plan WHERE plan_date=? ORDER BY planned_time NULLS LAST,created_at,id",PLAN,date);
    }
    public PlanApi.Item plan(String id) {
        var rows=jdbc.query("SELECT * FROM daily_plan WHERE id=?",PLAN,id);
        if(rows.isEmpty()) throw ApiException.notFound("日计划");
        return rows.getFirst();
    }
    public PlanApi.Item lockPlan(String id) {
        var rows=jdbc.query("SELECT * FROM daily_plan WHERE id=? FOR UPDATE",PLAN,id);
        if(rows.isEmpty()) throw ApiException.notFound("日计划");
        return rows.getFirst();
    }
}
