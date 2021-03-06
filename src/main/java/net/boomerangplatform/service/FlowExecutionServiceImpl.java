package net.boomerangplatform.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jgrapht.Graph;
import org.jgrapht.alg.interfaces.ShortestPathAlgorithm.SingleSourcePaths;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultEdge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import net.boomerangplatform.exceptions.InvalidWorkflowRuntimeException;
import net.boomerangplatform.exceptions.RunWorkflowException;
import net.boomerangplatform.model.Task;
import net.boomerangplatform.model.TaskResult;
import net.boomerangplatform.mongo.entity.FlowTaskExecutionEntity;
import net.boomerangplatform.mongo.entity.FlowTaskTemplateEntity;
import net.boomerangplatform.mongo.entity.FlowWorkflowActivityEntity;
import net.boomerangplatform.mongo.entity.FlowWorkflowRevisionEntity;
import net.boomerangplatform.mongo.model.CoreProperty;
import net.boomerangplatform.mongo.model.Dag;
import net.boomerangplatform.mongo.model.FlowTaskStatus;
import net.boomerangplatform.mongo.model.Revision;
import net.boomerangplatform.mongo.model.TaskType;
import net.boomerangplatform.mongo.model.next.DAGTask;
import net.boomerangplatform.mongo.model.next.Dependency;
import net.boomerangplatform.mongo.service.FlowTaskTemplateService;
import net.boomerangplatform.mongo.service.FlowWorkflowActivityService;
import net.boomerangplatform.mongo.service.FlowWorkflowVersionService;
import net.boomerangplatform.service.crud.FlowActivityService;
import net.boomerangplatform.service.runner.FlowTaskRunnerService;
import net.boomerangplatform.util.GraphProcessor;

@Service
public class FlowExecutionServiceImpl implements FlowExecutionService {

  @Autowired
  private FlowActivityService flowActivityService;

  @Autowired
  private FlowWorkflowVersionService flowRevisionService;

  @Autowired
  private FlowTaskRunnerService taskRunnerService;

  @Autowired
  private FlowTaskTemplateService taskTemplateService;

  @Autowired
  private FlowTaskTemplateService templateService;

  @Autowired
  private FlowWorkflowActivityService flowWorkflowActivityService;

  private static final Logger LOGGER = LogManager.getLogger(FlowExecutionServiceImpl.class);

  private List<Task> createTaskList(FlowWorkflowRevisionEntity revisionEntity) { // NOSONAR

    final Dag dag = revisionEntity.getDag();

    final List<Task> taskList = new LinkedList<>();
    for (final DAGTask dagTask : dag.getTasks()) {

      final Task newTask = new Task();
      newTask.setTaskId(dagTask.getTaskId());
      newTask.setTaskType(dagTask.getType());
      newTask.setTaskName(dagTask.getLabel());

      final String workFlowId = revisionEntity.getWorkFlowId();
      newTask.setWorkflowId(workFlowId);

      if (dagTask.getType() == TaskType.template || dagTask.getType() == TaskType.customtask) {
        String templateId = dagTask.getTemplateId();
        final FlowTaskTemplateEntity flowTaskTemplate =
            templateService.getTaskTemplateWithId(templateId);
        Integer templateVersion = dagTask.getTemplateVersion();
        List<Revision> revisions = flowTaskTemplate.getRevisions();
        if (revisions != null) {
          Optional<Revision> result = revisions.stream().parallel()
              .filter(revision -> revision.getVersion().equals(templateVersion)).findAny();
          if (result.isPresent()) {
            Revision revision = result.get();
            newTask.setRevision(revision);
          } else {
            Optional<Revision> latestRevision = revisions.stream()
                .sorted(Comparator.comparingInt(Revision::getVersion).reversed()).findFirst();
            if (latestRevision.isPresent()) {
              newTask.setRevision(latestRevision.get());
            }
          }
        } else {
          throw new IllegalArgumentException("Invalid task template selected: " + templateId);

        }

        Map<String, String> properties = new HashMap<>();
        if (dagTask.getProperties() != null) {
          for (CoreProperty property : dagTask.getProperties()) {
            properties.put(property.getKey(), property.getValue());
          }
        }

        newTask.setInputs(properties);

      } else if (dagTask.getType() == TaskType.decision) {
        newTask.setDecisionValue(dagTask.getDecisionValue());
      }

      final List<String> taskDepedancies = new LinkedList<>();
      for (Dependency dependency : dagTask.getDependencies()) {
        taskDepedancies.add(dependency.getTaskId());
      }
      newTask.setDetailedDepednacies(dagTask.getDependencies());

      newTask.setDependencies(taskDepedancies);
      taskList.add(newTask);
    }
    return taskList;
  }

  public void prepareExecution(List<Task> tasks, String activityId) {
    final Task start = getTaskByName(tasks, TaskType.start);
    final Task end = getTaskByName(tasks, TaskType.end);
    final Graph<String, DefaultEdge> graph = createGraph(tasks);
    validateWorkflow(activityId, start, end, graph);
    createTaskPlan(tasks, activityId, start, end, graph);
  }

  private void validateWorkflow(String activityId, final Task start, final Task end,
      final Graph<String, DefaultEdge> graph) {

    final FlowWorkflowActivityEntity activityEntity =
        this.flowWorkflowActivityService.findWorkflowActiivtyById(activityId);

    if (start == null || end == null) {
      activityEntity.setStatus(FlowTaskStatus.invalid);
      flowWorkflowActivityService.saveWorkflowActivity(activityEntity);
      throw new InvalidWorkflowRuntimeException();
    }

    final List<String> nodes =
        GraphProcessor.createOrderedTaskList(graph, start.getTaskId(), end.getTaskId());

    if (nodes.isEmpty()) {
      activityEntity.setStatus(FlowTaskStatus.invalid);
      flowWorkflowActivityService.saveWorkflowActivity(activityEntity);
      throw new InvalidWorkflowRuntimeException();
    }

    final DijkstraShortestPath<String, DefaultEdge> dijkstraAlg = new DijkstraShortestPath<>(graph);
    final SingleSourcePaths<String, DefaultEdge> pathFromStart =
        dijkstraAlg.getPaths(start.getTaskId());
    final boolean singlePathExists = (pathFromStart.getPath(end.getTaskId()) != null);
    if (!singlePathExists) {

      activityEntity.setStatus(FlowTaskStatus.invalid);
      activityEntity.setStatusMessage("Failed to run workflow: Incomplete workflow");
      this.flowWorkflowActivityService.saveWorkflowActivity(activityEntity);
      throw new InvalidWorkflowRuntimeException();
    }
  }

  private void createTaskPlan(List<Task> tasks, String activityId, final Task start, final Task end,
      final Graph<String, DefaultEdge> graph) {

    final List<String> nodes =
        GraphProcessor.createOrderedTaskList(graph, start.getTaskId(), end.getTaskId());
    final List<Task> tasksToRun = new LinkedList<>();
    for (final String node : nodes) {
      final Task taskToAdd =
          tasks.stream().filter(tsk -> node.equals(tsk.getTaskId())).findAny().orElse(null);
      tasksToRun.add(taskToAdd);
    }

    long order = 1;
    for (final Task task : tasksToRun) {

      final FlowTaskTemplateEntity taskTemplateEntity =
          taskTemplateService.getTaskTemplateWithId(task.getTaskId());
      FlowTaskExecutionEntity taskExecution = new FlowTaskExecutionEntity();
      taskExecution.setActivityId(activityId);
      taskExecution.setTaskId(task.getTaskId());
      taskExecution.setFlowTaskStatus(FlowTaskStatus.notstarted);
      taskExecution.setOrder(order);
      taskExecution.setTaskName(task.getTaskName());
      if (taskTemplateEntity != null) {
        taskExecution.setTaskName(taskTemplateEntity.getName());
      }

      taskExecution = this.flowActivityService.saveTaskExecution(taskExecution);

      task.setTaskActivityId(taskExecution.getId());
      order++;
    }
  }

  private Graph<String, DefaultEdge> createGraph(List<Task> tasks) {
    final List<String> vertices = tasks.stream().map(Task::getTaskId).collect(Collectors.toList());

    final List<Pair<String, String>> edgeList = new LinkedList<>();
    for (final Task task : tasks) {
      for (final String dep : task.getDependencies()) {
        final Pair<String, String> pair = Pair.of(dep, task.getTaskId());
        edgeList.add(pair);
      }
    }
    return GraphProcessor.createGraph(vertices, edgeList);
  }

  private Task getTaskByName(List<Task> tasks, TaskType type) {
    return tasks.stream().filter(tsk -> type.equals(tsk.getTaskType())).findAny().orElse(null);
  }

  private void executeWorkflowAsync(String activityId, final Task start, final Task end,
      final Graph<String, DefaultEdge> graph, final List<Task> tasksToRun)
      throws ExecutionException {

    CompletableFuture<TaskResult> result = taskRunnerService.runTasks(graph, tasksToRun, activityId,
        start.getTaskId(), end.getTaskId());
    try {
      result.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOGGER.error(e);
    }
  }

  @Override
  public CompletableFuture<Boolean> executeWorkflowVersion(String workFlowId, String activityId) {
    final FlowWorkflowRevisionEntity entity =
        this.flowRevisionService.getWorkflowlWithId(workFlowId);
    final List<Task> tasks = createTaskList(entity);
    prepareExecution(tasks, activityId);
    return CompletableFuture.supplyAsync(createProcess(activityId, tasks));
  }

  private Supplier<Boolean> createProcess(String activityId, List<Task> tasks) {
    return () -> {
      final Task start = getTaskByName(tasks, TaskType.start);
      final Task end = getTaskByName(tasks, TaskType.end);
      final Graph<String, DefaultEdge> graph = createGraph(tasks);
      try {
        executeWorkflowAsync(activityId, start, end, graph, tasks);
      } catch (ExecutionException e) {
        LOGGER.error(ExceptionUtils.getStackTrace(e));
        throw new RunWorkflowException();
      }
      return true;
    };
  }
}
