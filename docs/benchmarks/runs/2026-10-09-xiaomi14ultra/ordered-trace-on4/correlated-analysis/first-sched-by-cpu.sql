SELECT cpu,MIN(ts) AS first_sched_ns FROM sched GROUP BY cpu ORDER BY cpu;
