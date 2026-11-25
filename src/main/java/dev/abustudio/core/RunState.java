package dev.abustudio.core;

import java.util.ArrayList;
import java.util.List;

/** Everything needed to continue a task from the last confirmed point. Saved after every step. */
public class RunState
{
    public static class PlanItem
    {
        public String text;
        /** pending | doing | done | blocked */
        public String status = "pending";
    }

    public String id;
    public String title;
    public String request;
    /** RUNNING | WAITING_NETWORK | WAITING_PROVIDERS | PAUSED | WAITING_USER | DONE | FAILED */
    public String status = "RUNNING";
    public String statusNote = "";
    public long createdAt;
    public long updatedAt;
    public int step;
    public int nudges;
    public List<Llm.Msg> messages = new ArrayList<>();
    public List<PlanItem> plan = new ArrayList<>();
    public List<String> memory = new ArrayList<>();
    public List<String> userNotes = new ArrayList<>();
    public String lastModel = "";
    public String question = "";
    public boolean backedUp;
}
