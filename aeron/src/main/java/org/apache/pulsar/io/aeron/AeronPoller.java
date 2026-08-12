/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.io.aeron;

/**
 * The unit of work owned by the source's polling thread.
 *
 * <p>This exists so {@link AeronSource} does not hard-code the plain-transport poll loop.
 * An Aeron Archive backed implementation — replaying from a recorded position and
 * checkpointing that position so restarts do not lose data — can be added later as a second
 * implementation without reworking the source's lifecycle.
 */
public interface AeronPoller extends Runnable {

    /**
     * Signals the loop to finish. Returns without waiting; the caller joins the thread.
     *
     * <p>Must be safe to call from another thread and safe to call more than once.
     */
    void stop();
}
