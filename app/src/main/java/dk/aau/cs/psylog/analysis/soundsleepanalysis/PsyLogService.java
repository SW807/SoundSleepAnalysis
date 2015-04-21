package dk.aau.cs.psylog.analysis.soundsleepanalysis;

import dk.aau.cs.psylog.module_lib.ScheduledService;

public class PsyLogService extends ScheduledService{
    public PsyLogService(){
        super("SoundSleepAnalysis");
    }

    @Override
    public void setScheduledTask() {
        this.scheduledTask = new SoundSleepAnalysis(this);
    }
}
