/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.protocol.Errors;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of an asynchronous request from {@link ConsumerNetworkClient}. Use {@link ConsumerNetworkClient#poll(long)}
 * (and variants) to finish a request future. Use {@link #isDone()} to check if the future is complete, and
 * {@link #succeeded()} to check if the request completed successfully. Typical usage might look like this:
 *
 * <pre>
 *     RequestFuture<ClientResponse> future = client.send(api, request);
 *     client.poll(future);
 *
 *     if (future.succeeded()) {
 *         ClientResponse response = future.value();
 *         // Handle response
 *     } else {
 *         throw future.exception();
 *     }
 * </pre>
 *
 * @param <T> Return type of the result (Can be Void if there is no response)
 */
public class RequestFuture<T> {

    private boolean isDone = false; //表示当前请求是否已经完成，注意只要是被处理过就设置为true,不管有无异常
    private T value;    //请求正常完成时收到的相应
    private RuntimeException exception; //请求异常时得到的相应
    private List<RequestFutureListener<T>> listeners = new ArrayList<>();   //监听请求完成的情况，含有onSuccess和onFailure


    /**
     * Check whether the response is ready to be handled
     * @return true if the response is ready, false otherwise
     */
    public boolean isDone() {
        return isDone;
    }

    /**
     * Get the value corresponding to this request (only available if the request succeeded)
     * @return the value if it exists or null
     */
    public T value() {
        return value;
    }

    /**
     * Check if the request succeeded;
     * @return true if the request completed and was successful
     */
    public boolean succeeded() {
        return isDone && exception == null;
    }

    /**
     * Check if the request failed.
     * @return true if the request completed with a failure
     */
    public boolean failed() {
        return isDone && exception != null;
    }

    /**
     * Check if the request is retriable (convenience method for checking if
     * the exception is an instance of {@link RetriableException}.
     * @return true if it is retriable, false otherwise
     */
    public boolean isRetriable() {
        return exception instanceof RetriableException;
    }

    /**
     * Get the exception from a failed result (only available if the request failed)
     * @return The exception if it exists or null
     */
    public RuntimeException exception() {
        return exception;
    }

    /**
     * Complete the request successfully. After this call, {@link #succeeded()} will return true
     * and the value can be obtained through {@link #value()}.
     * @param value corresponding value (or null if there is none)
     */
    public void complete(T value) {
        if (isDone)
            throw new IllegalStateException("Invalid attempt to complete a request future which is already complete");
        this.value = value;
        this.isDone = true;
        fireSuccess();
    }

    /**
     * Raise an exception. The request will be marked as failed, and the caller can either
     * handle the exception or throw it.
     * @param e corresponding exception to be passed to caller
     */
    public void raise(RuntimeException e) {
        if (isDone)
            throw new IllegalStateException("Invalid attempt to complete a request future which is already complete");
        this.exception = e;
        this.isDone = true;
        fireFailure();
    }

    /**
     * Raise an error. The request will be marked as failed.
     * @param error corresponding error to be passed to caller
     */
    public void raise(Errors error) {
        raise(error.exception());
    }

    private void fireSuccess() {
        for (RequestFutureListener<T> listener : listeners)
            listener.onSuccess(value);
    }

    private void fireFailure() {
        for (RequestFutureListener<T> listener : listeners)
            listener.onFailure(exception);
    }

    /**
     * Add a listener which will be notified when the future completes
     * @param listener
     */
    public void addListener(RequestFutureListener<T> listener) {
        if (isDone) {
            if (exception != null)
                listener.onFailure(exception);
            else
                listener.onSuccess(value);
        } else {
            this.listeners.add(listener);
        }
    }

    /**
     * Convert from a request future of one type to another type
     * @param adapter The adapter which does the conversion
     * @param <S> The type of the future adapted to
     * @return The new future
     * 典型的适配器模式应用，将<T>适配成<S>
     */
    public <S> RequestFuture<S> compose(final RequestFutureAdapter<T, S> adapter) {
        final RequestFuture<S> adapted = new RequestFuture<>();
        addListener(new RequestFutureListener<T>() {
            @Override
            public void onSuccess(T value) {
                adapter.onSuccess(value, adapted);
            }

            @Override
            public void onFailure(RuntimeException e) {
                adapter.onFailure(e, adapted);
            }
        });
        return adapted;
    }

    /**
     * 责任链的应用，通过RequestFutureListener在多个RequestFuture之间传递事件
     * @param future
     */
    public void chain(final RequestFuture<T> future) {
        addListener(new RequestFutureListener<T>() {    //添加监听器
            @Override
            public void onSuccess(T value) {
                //通过监听器将value传递给下一个RequestFuture对象
                future.complete(value);
            }

            @Override
            public void onFailure(RuntimeException e) {
                future.raise(e);
            }
        });
    }

    public static <T> RequestFuture<T> failure(RuntimeException e) {
        RequestFuture<T> future = new RequestFuture<T>();
        future.raise(e);
        return future;
    }

    public static RequestFuture<Void> voidSuccess() {
        RequestFuture<Void> future = new RequestFuture<Void>();
        future.complete(null);
        return future;
    }

    public static <T> RequestFuture<T> coordinatorNotAvailable() {
        return failure(Errors.GROUP_COORDINATOR_NOT_AVAILABLE.exception());
    }

    public static <T> RequestFuture<T> leaderNotAvailable() {
        return failure(Errors.LEADER_NOT_AVAILABLE.exception());
    }

    public static <T> RequestFuture<T> noBrokersAvailable() {
        return failure(new NoAvailableBrokersException());
    }

    public static <T> RequestFuture<T> staleMetadata() {
        return failure(new StaleMetadataException());
    }

}
