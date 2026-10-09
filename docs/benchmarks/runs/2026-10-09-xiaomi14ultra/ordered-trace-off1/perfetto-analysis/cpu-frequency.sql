SELECT c.ts,t.cpu,t.name,c.value FROM counter c
JOIN cpu_counter_track t ON c.track_id=t.id WHERE t.name='cpufreq' ORDER BY c.ts,t.cpu;
