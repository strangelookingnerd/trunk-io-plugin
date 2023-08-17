package io.trunk.jenkins;

import com.google.gson.Gson;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.trunk.jenkins.client.TrunkClient;
import io.trunk.jenkins.model.Metadata;
import io.trunk.jenkins.model.Repo;
import io.trunk.jenkins.model.event.ActivityEventForm;
import io.trunk.jenkins.model.service.TrackEventsRequest;
import okhttp3.OkHttpClient;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ActivityHandler {

    private static final Logger LOG = Logger.getLogger(ActivityHandler.class.getName());
    private final Gson gson = new Gson();
    private final OkHttpClient http = new OkHttpClient();
    private final Map<String, List<Repo>> reposByRunId = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    /**
     * Pipeline events.
     */

    public void onPipelineStarted(@NonNull WorkflowRun run, List<Repo> repos) {
        reposByRunId.put(run.getId(), repos);
        final var event = Mapper.newPipelineStartedEvent(run);
        trackEventForRepos(repos, event);
    }

    public void onPipelineCompleted(@NonNull WorkflowRun run) {
        final var repos = reposByRunId.get(run.getId());
        reposByRunId.remove(run.getId());
        if (repos != null) {
            final var event = Mapper.newPipelineCompletedEvent(run);
            trackEventForRepos(repos, event);
        }
    }

    /**
     * Stage events.
     */

    public void onStageStarted(@NonNull FlowNode node) {
        try {
            final var run = (WorkflowRun) node.getExecution().getOwner().getExecutable();
            final var repos = reposByRunId.get(run.getId());
            if (repos == null) {
                return;
            }
            final var event = Mapper.newStageStartedEvent(run, node);
            trackEventForRepos(repos, event);

        } catch (IOException e) {
            LOG.warning(e.getMessage());
        }
    }

    public void onStageCompleted(@NonNull FlowNode startNode, @NonNull FlowNode endNode) {
        try {
            final var run = (WorkflowRun) startNode.getExecution().getOwner().getExecutable();
            final var repos = reposByRunId.get(run.getId());
            if (repos == null) {
                return;
            }
            final var event = Mapper.newStageCompletedEvent(run, startNode, endNode);
            trackEventForRepos(repos, event);
        } catch (IOException e) {
            LOG.warning(e.getMessage());
        }
    }

    /**
     * Call http client to track events using a thread pool without blocking the main thread.
     */
    private void trackEventForRepos(
            @NonNull List<Repo> repos,
            @NonNull ActivityEventForm event) {

        final var cfg = Configuration.get();
        final var client = new TrunkClient(http, gson, cfg.trunkApi);
        final var md = Metadata.make(cfg.token);

        try {
            pool.invokeAll(repos.stream().map((repo) -> (Callable<Object>) () -> {
                client.trackEvents(TrackEventsRequest.forSingleEvent(repo, event), md);
                return null;
            }).collect(Collectors.toList()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warning(e.getMessage());
        }
    }

}
