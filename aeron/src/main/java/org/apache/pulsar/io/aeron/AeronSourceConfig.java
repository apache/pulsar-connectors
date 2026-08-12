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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.aeron.ChannelUri;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Data;
import lombok.experimental.Accessors;
import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.io.core.annotations.FieldDoc;

/**
 * Aeron Source Connector Config.
 */
@Data
@Accessors(chain = true)
public class AeronSourceConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Read the live Aeron transport. At-most-once; nothing can be replayed. */
    public static final String MODE_TRANSPORT = "transport";
    /** Replay from an Aeron Archive recording. Recoverable across restarts. */
    public static final String MODE_ARCHIVE = "archive";

    private static final List<String> MODES = Arrays.asList(MODE_TRANSPORT, MODE_ARCHIVE);

    private static final int DEFAULT_CHECKPOINT_EVERY_RECORDS = 1000;

    @FieldDoc(
            required = false,
            defaultValue = "transport",
            help = "Where the source reads from. 'transport' reads the live Aeron stream and is "
                    + "at-most-once: nothing can be replayed, so messages published while the "
                    + "connector is down are lost. 'archive' replays from an Aeron Archive "
                    + "recording and can resume after a restart. The two have materially "
                    + "different delivery guarantees, so the mode is explicit rather than a flag")
    private String mode = MODE_TRANSPORT;

    @FieldDoc(
            required = true,
            defaultValue = "",
            help = "The Aeron channel URI to subscribe to, for example "
                    + "'aeron:udp?endpoint=0.0.0.0:40123' or 'aeron:ipc'")
    private String channel;

    @FieldDoc(
            required = false,
            defaultValue = "1001",
            help = "The Aeron stream id to subscribe to within the channel. Must not be 0")
    private int streamId = 1001;

    @FieldDoc(
            required = false,
            defaultValue = "true",
            help = "Whether to launch an embedded Aeron media driver. When false, "
                    + "'aeronDirectoryName' must point at the directory of an externally "
                    + "managed media driver")
    private boolean useEmbeddedMediaDriver = true;

    @FieldDoc(
            required = false,
            defaultValue = "",
            help = "The Aeron media driver directory. Required when "
                    + "'useEmbeddedMediaDriver' is false; optional otherwise, in which case "
                    + "the embedded driver picks its own directory")
    private String aeronDirectoryName;

    @FieldDoc(
            required = false,
            defaultValue = "backoff",
            help = "The idle strategy used by the polling thread when no fragments are "
                    + "available. Supported values are 'backoff', 'sleeping', 'yielding' "
                    + "and 'busyspin'. 'busyspin' gives the lowest latency but pins a core")
    private String idleStrategy = IdleStrategies.BACKOFF;

    @FieldDoc(
            required = false,
            defaultValue = "256",
            help = "The maximum number of fragments to read in a single poll")
    private int fragmentLimit = 256;

    @FieldDoc(
            required = false,
            defaultValue = "false",
            help = "When true, the record key is set to the Aeron session id, which lets "
                    + "key_shared subscriptions and topic compaction group by Aeron session")
    private boolean keyBySessionId = false;

    @FieldDoc(
            required = false,
            defaultValue = "0",
            help = "Initial buffer length, in bytes, used by the fragment assembler for "
                    + "reassembling messages larger than the MTU. 0 uses the Aeron default")
    private int fragmentAssemblyBufferLength = 0;

    // ---- archive mode ----------------------------------------------------------------
    // All null / -1 by default so "the user set this" is distinguishable from "unset", which is
    // what lets validate() reject archive settings supplied in transport mode instead of
    // silently ignoring them.

    @FieldDoc(
            required = false,
            defaultValue = "",
            help = "Archive control request channel, for example "
                    + "'aeron:udp?endpoint=localhost:8010'. Required when mode is 'archive'")
    private String archiveControlRequestChannel;

    @FieldDoc(
            required = false,
            defaultValue = "",
            help = "Archive control response channel, for example "
                    + "'aeron:udp?endpoint=localhost:0'. Required when mode is 'archive'")
    private String archiveControlResponseChannel;

    @FieldDoc(
            required = false,
            defaultValue = "-1",
            help = "The recording to replay. -1 discovers the most recent recording matching "
                    + "'channel' and 'streamId'. Only used when mode is 'archive'")
    private long recordingId = -1L;

    @FieldDoc(
            required = false,
            defaultValue = "-1",
            help = "Position within the recording to start replaying from. -1 starts at the "
                    + "beginning of the recording. Only used when mode is 'archive'")
    private long startPosition = -1L;

    @FieldDoc(
            required = false,
            defaultValue = "",
            help = "Channel the archive replays onto, for example 'aeron:ipc'. Required when "
                    + "mode is 'archive'")
    private String replayChannel;

    @FieldDoc(
            required = false,
            defaultValue = "-1",
            help = "Stream id used for the replay leg. Must differ from 'streamId' when the "
                    + "replay channel is the same as the subscription channel. Required when "
                    + "mode is 'archive'")
    private int replayStreamId = -1;

    @FieldDoc(
            required = false,
            defaultValue = "1000",
            help = "How many records to emit between checkpoints. This is the at-least-once "
                    + "window: a crash replays at most this many records on restart. Lower values "
                    + "shrink the duplicate window at the cost of more state-store writes. Only "
                    + "used when mode is 'archive'")
    private int checkpointEveryRecords = DEFAULT_CHECKPOINT_EVERY_RECORDS;

    @FieldDoc(
            required = false,
            defaultValue = "false",
            help = "Discards the stored checkpoint at startup and begins again from 'recordingId' "
                    + "and 'startPosition'. This is a ONE-SHOT operational flag for deliberately "
                    + "reprocessing history: set it, start the connector once, then REMOVE it. "
                    + "Left in place it discards the checkpoint on every restart, so the connector "
                    + "re-ingests the whole recording each time it comes back. Only used when mode "
                    + "is 'archive'")
    private boolean resetCheckpoint = false;

    public boolean isArchiveMode() {
        return MODE_ARCHIVE.equalsIgnoreCase(StringUtils.trimToEmpty(mode));
    }

    public static AeronSourceConfig load(Map<String, Object> map) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(mapper.writeValueAsString(map), AeronSourceConfig.class);
    }

    public static AeronSourceConfig load(String yamlFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File(yamlFile), AeronSourceConfig.class);
    }

    /**
     * Validates the configuration, failing fast at {@code open()} time rather than letting
     * a bad channel URI surface later as an obscure Aeron driver error.
     *
     * @throws IllegalArgumentException if any value is missing or unusable
     */
    public void validate() {
        if (StringUtils.isBlank(channel)) {
            throw new IllegalArgumentException("channel must be set");
        }
        try {
            ChannelUri.parse(channel);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("channel is not a valid Aeron URI: " + channel, e);
        }
        // Aeron reserves stream id 0, so a 0 here is nearly always an unset field rather
        // than a deliberate choice.
        if (streamId == 0) {
            throw new IllegalArgumentException("streamId must not be 0");
        }
        if (!IdleStrategies.isSupported(idleStrategy)) {
            throw new IllegalArgumentException("idleStrategy must be one of "
                    + IdleStrategies.supportedStrategies() + " but was: " + idleStrategy);
        }
        if (fragmentLimit <= 0) {
            throw new IllegalArgumentException("fragmentLimit must be positive but was: " + fragmentLimit);
        }
        if (fragmentAssemblyBufferLength < 0) {
            throw new IllegalArgumentException(
                    "fragmentAssemblyBufferLength must not be negative but was: " + fragmentAssemblyBufferLength);
        }
        // Without an embedded driver there is nothing to point the Aeron client at unless
        // the caller names the external driver's directory.
        if (!useEmbeddedMediaDriver && StringUtils.isBlank(aeronDirectoryName)) {
            throw new IllegalArgumentException(
                    "aeronDirectoryName must be set when useEmbeddedMediaDriver is false");
        }
        if (mode == null || !MODES.contains(mode.toLowerCase(Locale.ROOT).trim())) {
            throw new IllegalArgumentException(
                    "mode must be one of " + MODES + " but was: " + mode);
        }
        validateArchiveSettings();
    }

    /**
     * Archive settings are required together in archive mode, and rejected outright in transport
     * mode.
     *
     * <p>Rejecting rather than ignoring is deliberate. A half-configured archive setup that
     * silently runs as at-most-once transport is the worst outcome available here: it looks like
     * the lossless mode and behaves like the lossy one.
     */
    private void validateArchiveSettings() {
        if (isArchiveMode()) {
            requireArchiveField(archiveControlRequestChannel, "archiveControlRequestChannel");
            requireArchiveField(archiveControlResponseChannel, "archiveControlResponseChannel");
            requireArchiveField(replayChannel, "replayChannel");
            if (replayStreamId == -1) {
                throw new IllegalArgumentException(
                        "replayStreamId must be set when mode is '" + MODE_ARCHIVE + "'");
            }
            if (replayStreamId == 0) {
                throw new IllegalArgumentException("replayStreamId must not be 0");
            }
            if (checkpointEveryRecords <= 0) {
                throw new IllegalArgumentException(
                        "checkpointEveryRecords must be positive but was: " + checkpointEveryRecords);
            }
            // Same channel and same stream id would make the replay collide with the live
            // subscription this source is not even using in archive mode.
            if (replayStreamId == streamId && replayChannel.equals(channel)) {
                throw new IllegalArgumentException(
                        "replayStreamId must differ from streamId when replayChannel equals channel");
            }
            return;
        }

        final List<String> unexpected = new ArrayList<>();
        if (StringUtils.isNotBlank(archiveControlRequestChannel)) {
            unexpected.add("archiveControlRequestChannel");
        }
        if (StringUtils.isNotBlank(archiveControlResponseChannel)) {
            unexpected.add("archiveControlResponseChannel");
        }
        if (StringUtils.isNotBlank(replayChannel)) {
            unexpected.add("replayChannel");
        }
        if (replayStreamId != -1) {
            unexpected.add("replayStreamId");
        }
        if (recordingId != -1L) {
            unexpected.add("recordingId");
        }
        if (startPosition != -1L) {
            unexpected.add("startPosition");
        }
        if (checkpointEveryRecords != DEFAULT_CHECKPOINT_EVERY_RECORDS) {
            unexpected.add("checkpointEveryRecords");
        }
        if (resetCheckpoint) {
            unexpected.add("resetCheckpoint");
        }
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException(
                    "these settings only apply when mode is '" + MODE_ARCHIVE + "': " + unexpected
                            + ". Set mode to '" + MODE_ARCHIVE + "' or remove them — leaving them "
                            + "in place with mode '" + MODE_TRANSPORT + "' would run at-most-once "
                            + "while looking configured for lossless replay");
        }
    }

    private static void requireArchiveField(String value, String name) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(
                    name + " must be set when mode is '" + MODE_ARCHIVE + "'");
        }
    }
}
