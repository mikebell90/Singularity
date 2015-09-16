package com.hubspot.singularity.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.executor.SingularityExecutorMonitor.KillState;
import com.hubspot.singularity.executor.config.SingularityExecutorConfiguration;
import com.hubspot.singularity.executor.task.SingularityExecutorTaskProcessCallable;
import com.hubspot.singularity.runner.base.shared.ProcessFailedException;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerException;

@Singleton
public class SingularityExecutorThreadChecker {

  private static final Logger LOG = LoggerFactory.getLogger(SingularityExecutorThreadChecker.class);

  private static Pattern CGROUP_CONTAINER_REGEX = Pattern.compile("^\\d:cpu:/(.*)$");

  private final SingularityExecutorConfiguration configuration;
  private final ScheduledExecutorService scheduledExecutorService;
  private final DockerClient dockerClient;

  private SingularityExecutorMonitor monitor;

  @Inject
  public SingularityExecutorThreadChecker(SingularityExecutorConfiguration configuration, DockerClient dockerClient) {
    this.configuration = configuration;
    this.dockerClient = dockerClient;

    this.scheduledExecutorService = Executors.newScheduledThreadPool(configuration.getThreadCheckThreads(), new ThreadFactoryBuilder().setNameFormat("SingularityExecutorThreadCheckerThread-%d").build());
  }

  public void start(SingularityExecutorMonitor monitor) {

    LOG.info("Starting a thread checker that will run every {}", JavaUtils.durationFromMillis(configuration.getCheckThreadsEveryMillis()));

    this.monitor = monitor;

    this.scheduledExecutorService.scheduleAtFixedRate(new Runnable() {

      @Override
      public void run() {
        final long start = System.currentTimeMillis();

        try {
          checkThreads();
        } catch (Throwable t) {
          LOG.error("While checking threads", t);
        } finally {
          LOG.trace("Finished checking threads after {}", JavaUtils.duration(start));
        }
      }
    }, configuration.getCheckThreadsEveryMillis(), configuration.getCheckThreadsEveryMillis(), TimeUnit.MILLISECONDS);
  }

  private void checkThreads() {
    for (SingularityExecutorTaskProcessCallable taskProcess : monitor.getRunningTasks()) {
      if (!taskProcess.getTask().getExecutorData().getMaxTaskThreads().isPresent()) {
        continue;
      }

      final int maxThreads = taskProcess.getTask().getExecutorData().getMaxTaskThreads().get();

      int usedThreads = 0;

      try {
        usedThreads = getNumUsedThreads(taskProcess);
        LOG.trace("{} is using {} threads", taskProcess.getTask().getTaskId(), usedThreads);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return;
      } catch (Throwable t) {
        taskProcess.getTask().getLog().error("While fetching used threads for {}", taskProcess.getTask().getTaskId(), t);
        continue;
      }

      if (usedThreads > maxThreads) {
        taskProcess.getTask().getLog().info("{} using too many threads: {} (max {})", taskProcess.getTask().getTaskId(), usedThreads, maxThreads);

        taskProcess.getTask().markKilledDueToThreads(usedThreads);
        KillState killState = monitor.requestKill(taskProcess.getTask().getTaskId());

        taskProcess.getTask().getLog().info("Killing {} due to thread overage (kill state {})", taskProcess.getTask().getTaskId(), killState);
      }
    }
  }

  public ExecutorService getExecutorService() {
    return scheduledExecutorService;
  }

  private int getNumUsedThreads(SingularityExecutorTaskProcessCallable taskProcess) throws InterruptedException, ProcessFailedException {
    Optional<Integer> dockerPid = Optional.absent();
    if (taskProcess.getTask().getTaskInfo().hasContainer() && taskProcess.getTask().getTaskInfo().getContainer().hasDocker()) {
      try {
        String containerName = String.format("%s%s", configuration.getDockerPrefix(), taskProcess.getTask().getTaskId());
        dockerPid = Optional.of(dockerClient.inspectContainer(containerName).state().pid());
      } catch (DockerException e) {
        throw new ProcessFailedException(String.format("Could not get docker root pid due to error: %s", e));
      }
    }

    try {
      final Path procCgroupPath = Paths.get(String.format(configuration.getProcCgroupFormat(), dockerPid.or(taskProcess.getCurrentPid().get())));
      if (Files.exists(procCgroupPath)) {
        final String cgroupsInfo = new String(Files.readAllBytes(procCgroupPath), Charsets.UTF_8);
        final Matcher matcher = CGROUP_CONTAINER_REGEX.matcher(cgroupsInfo);

        if (!matcher.matches()) {
          throw new RuntimeException("Unable to parse cgroup container from " + procCgroupPath.toString());
        }

        return Files.readAllLines(Paths.get(String.format(configuration.getCgroupsMesosCpuTasksFormat(), matcher.group(1))), Charsets.UTF_8).size();
      } else {
        throw new RuntimeException(procCgroupPath.toString() + " does not exist");
      }
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

}
