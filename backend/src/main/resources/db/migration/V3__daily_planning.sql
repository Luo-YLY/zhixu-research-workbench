CREATE TABLE recurring_schedule (
  id VARCHAR(36) PRIMARY KEY,
  task_id VARCHAR(36) NOT NULL REFERENCES research_task(id),
  frequency VARCHAR(12) NOT NULL,
  weekday INTEGER,
  planned_time TIME NOT NULL,
  start_date DATE NOT NULL,
  active BOOLEAN NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT schedule_frequency_ck CHECK (frequency IN ('DAILY','WEEKLY')),
  CONSTRAINT schedule_weekday_ck CHECK ((frequency='DAILY' AND weekday IS NULL) OR (frequency='WEEKLY' AND weekday BETWEEN 1 AND 7))
);
CREATE INDEX schedule_task_idx ON recurring_schedule(task_id);

CREATE TABLE daily_plan (
  id VARCHAR(36) PRIMARY KEY,
  plan_date DATE NOT NULL,
  planned_time TIME,
  task_id VARCHAR(36) REFERENCES research_task(id),
  schedule_id VARCHAR(36) REFERENCES recurring_schedule(id),
  title VARCHAR(180) NOT NULL,
  status VARCHAR(12) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT plan_status_ck CHECK (status IN ('TODO','DONE')),
  CONSTRAINT plan_schedule_date_uq UNIQUE(schedule_id,plan_date)
);
CREATE INDEX plan_date_idx ON daily_plan(plan_date,planned_time);
