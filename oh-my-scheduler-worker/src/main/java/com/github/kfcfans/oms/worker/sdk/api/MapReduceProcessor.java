package com.github.kfcfans.oms.worker.sdk.api;

import akka.actor.ActorSelection;
import akka.pattern.Patterns;
import com.github.kfcfans.oms.worker.OhMyWorker;
import com.github.kfcfans.oms.worker.common.ThreadLocalStore;
import com.github.kfcfans.common.AkkaConstant;
import com.github.kfcfans.oms.worker.common.constants.TaskConstant;
import com.github.kfcfans.oms.worker.common.utils.AkkaUtils;
import com.github.kfcfans.oms.worker.pojo.request.ProcessorMapTaskRequest;
import com.github.kfcfans.common.response.AskResponse;
import com.github.kfcfans.oms.worker.sdk.TaskContext;
import com.github.kfcfans.oms.worker.sdk.ProcessResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * MapReduce执行处理器，适用于MapReduce任务
 *
 * @author tjq
 * @since 2020/3/18
 */
@Slf4j
public abstract class MapReduceProcessor implements BasicProcessor {

    private static final int RECOMMEND_BATCH_SIZE = 200;
    private static final int REQUEST_TIMEOUT_MS = 5000;

    /**
     * 分发子任务
     * @param taskList 子任务，再次执行时可通过 TaskContext#getSubTask 获取
     * @param taskName 子任务名称，作用不大
     * @return map结果
     */
    public ProcessResult map(List<?> taskList, String taskName) {

        if (CollectionUtils.isEmpty(taskList)) {
            return new ProcessResult(false, "taskList can't be null");
        }

        if (taskList.size() > RECOMMEND_BATCH_SIZE) {
            log.warn("[MapReduceProcessor] map task size is too large, network maybe overload... please try to split the tasks.");
        }

        TaskContext taskContext = ThreadLocalStore.TASK_CONTEXT_THREAD_LOCAL.get();

        // 1. 构造请求
        ProcessorMapTaskRequest req = new ProcessorMapTaskRequest(taskContext, taskList, taskName);

        // 2. 可靠发送请求（任务不允许丢失，需要使用 ask 方法，失败抛异常）
        boolean requestSucceed = false;
        try {
            String akkaRemotePath = AkkaUtils.getAkkaRemotePath(taskContext.getTaskTrackerAddress(), AkkaConstant.Task_TRACKER_ACTOR_NAME);
            ActorSelection actorSelection = OhMyWorker.actorSystem.actorSelection(akkaRemotePath);
            CompletionStage<Object> requestCS = Patterns.ask(actorSelection, req, Duration.ofMillis(REQUEST_TIMEOUT_MS));
            AskResponse respObj = (AskResponse) requestCS.toCompletableFuture().get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            requestSucceed = respObj.isSuccess();
        }catch (Exception e) {
            log.warn("[MapReduceProcessor] map failed.", e);
        }

        if (requestSucceed) {
            return new ProcessResult(true, "MAP_SUCCESS");
        }else {
            return new ProcessResult(false, "MAP_FAILED");
        }
    }

    public boolean isRootTask() {
        TaskContext taskContext = ThreadLocalStore.TASK_CONTEXT_THREAD_LOCAL.get();
        return TaskConstant.ROOT_TASK_ID.equals(taskContext.getTaskId());
    }

    public abstract ProcessResult reduce(TaskContext taskContext, Map<String, String> taskId2Result);
}
