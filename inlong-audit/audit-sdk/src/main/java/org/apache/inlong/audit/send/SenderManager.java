/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.audit.send;

import org.apache.inlong.audit.protocol.AuditApi;
import org.apache.inlong.audit.util.AuditConfig;
import org.apache.inlong.audit.util.AuditData;
import org.apache.inlong.audit.util.SenderResult;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Audit sender manager
 */
public class SenderManager {

    public static final Long MAX_REQUEST_ID = 1000000000L;
    public static final int ALL_CONNECT_CHANNEL = -1;
    public static final int DEFAULT_CONNECT_CHANNEL = 2;
    public static final Logger LOG = LoggerFactory.getLogger(SenderManager.class);
    private static final int SEND_INTERVAL_MS = 20;
    private final SecureRandom sRandom = new SecureRandom(Long.toString(System.currentTimeMillis()).getBytes());
    private final AtomicLong requestIdSeq = new AtomicLong(0L);
    private final ConcurrentHashMap<Long, AuditData> dataMap = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Long> requestIdQueue = new LinkedBlockingQueue<>();

    private SenderGroup sender;
    private int maxConnectChannels = ALL_CONNECT_CHANNEL;
    // IPList
    private List<String> currentIpPorts = new ArrayList<>();
    private AuditConfig auditConfig;
    private long lastCheckTime = System.currentTimeMillis();

    /**
     * Constructor
     */
    public SenderManager(AuditConfig config) {
        this(config, DEFAULT_CONNECT_CHANNEL);
    }

    /**
     * Constructor
     */
    public SenderManager(AuditConfig config, int maxConnectChannels) {
        try {
            this.auditConfig = config;
            this.maxConnectChannels = maxConnectChannels;
            this.sender = new SenderGroup(this);
        } catch (Exception ex) {
            LOG.error(ex.getMessage(), ex);
        }
    }

    /**
     * update config
     */
    public void setAuditProxy(HashSet<String> ipPortList) {
        if (ipPortList.equals(currentIpPorts) && !this.sender.isHasSendError()) {
            return;
        }
        this.sender.setHasSendError(false);
        List<String> newIpPorts = new ArrayList<>();
        newIpPorts.addAll(ipPortList);
        this.currentIpPorts = newIpPorts;
        int ipSize = ipPortList.size();
        int needNewSize;
        if (this.maxConnectChannels == ALL_CONNECT_CHANNEL || this.maxConnectChannels >= ipSize) {
            needNewSize = ipSize;
        } else {
            needNewSize = maxConnectChannels;
        }

        List<String> updateConfigIpLists = new ArrayList<>();
        List<String> availableIpLists = new ArrayList<>(ipPortList);
        for (int i = 0; i < needNewSize; i++) {
            int availableIpSize = availableIpLists.size();
            int newIpPortIndex = this.sRandom.nextInt(availableIpSize);
            String ipPort = availableIpLists.remove(newIpPortIndex);
            updateConfigIpLists.add(ipPort);
        }
        LOG.info("needNewSize:{},updateConfigIpLists:{}", needNewSize, updateConfigIpLists);
        if (updateConfigIpLists.size() > 0) {
            this.sender.updateConfig(updateConfigIpLists);
        }
    }

    /**
     * next request id
     */
    public Long nextRequestId() {
        long requestId = requestIdSeq.getAndIncrement();
        if (requestId > MAX_REQUEST_ID) {
            requestId = 0L;
            requestIdSeq.set(requestId);
        }
        return requestId;
    }

    /**
     * Send data with command
     */
    public void send(AuditApi.BaseCommand baseCommand, AuditApi.AuditRequest auditRequest) {
        AuditData data = new AuditData(baseCommand, auditRequest);
        // cache first
        Long requestId = baseCommand.getAuditRequest().getRequestId();
        this.dataMap.putIfAbsent(requestId, data);
        requestIdQueue.offer(requestId);
        this.sendData(data.getDataByte());
        // resend
        long newTime = System.currentTimeMillis() - 10000;
        if (newTime > lastCheckTime) {
            for (int i = 0; i < requestIdQueue.size(); i++) {
                Long current = requestIdQueue.poll();
                AuditData auditData = this.dataMap.get(current);
                if (auditData == null) {
                    continue;
                } else {
                    requestIdQueue.offer(current);
                    if (newTime > auditData.getSendTime()) {
                        this.sendData(auditData.getDataByte());
                    }
                }
            }
        }
    }

    /**
     * Send data byte array
     */
    private void sendData(byte[] data) {
        if (data == null || data.length <= 0) {
            LOG.warn("send data is empty!");
            return;
        }
        ByteBuf dataBuf = ByteBufAllocator.DEFAULT.buffer(data.length);
        dataBuf.writeBytes(data);
        SenderResult result = this.sender.send(dataBuf);
        if (!result.result) {
            this.sender.setHasSendError(true);
        }
    }

    /**
     * Clean up the backlog of unsent message packets
     */
    public void clearBuffer() {
        LOG.info("audit failed cache size: {}", this.dataMap.size());
        for (AuditData data : this.dataMap.values()) {
            this.sendData(data.getDataByte());
            this.sleep();
        }
        if (this.dataMap.size() == 0) {
            checkAuditFile();
        }
        if (this.dataMap.size() > auditConfig.getMaxCacheRow()) {
            LOG.info("failed cache size: {}>{}", this.dataMap.size(), auditConfig.getMaxCacheRow());
            writeLocalFile();
            this.dataMap.clear();
        }
    }

    /**
     * write local file
     */
    private void writeLocalFile() {
        try {
            if (!checkFilePath()) {
                return;
            }
            File file = new File(auditConfig.getDisasterFile());
            if (!file.exists()) {
                if (!file.createNewFile()) {
                    LOG.error("create file {} failed", auditConfig.getDisasterFile());
                    return;
                }
                LOG.info("create file {} success", auditConfig.getDisasterFile());
            }
            if (file.length() > auditConfig.getMaxFileSize()) {
                file.delete();
                return;
            }
            FileOutputStream fos = new FileOutputStream(file);
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(fos);
            objectOutputStream.writeObject(dataMap);
            objectOutputStream.close();
            fos.close();
        } catch (IOException e) {
            LOG.error("write local file error:{}", e.getMessage(), e);
        }
    }

    /**
     * check file path
     */
    private boolean checkFilePath() {
        File file = new File(auditConfig.getFilePath());
        if (!file.exists()) {
            if (!file.mkdirs()) {
                return false;
            }
            LOG.info("create file {} success", auditConfig.getFilePath());
        }
        return true;
    }

    /**
     * check audit file
     */
    private void checkAuditFile() {
        try {
            File file = new File(auditConfig.getDisasterFile());
            if (!file.exists()) {
                return;
            }
            FileInputStream inputStream = new FileInputStream(auditConfig.getDisasterFile());
            ObjectInputStream objectStream = new ObjectInputStream(inputStream);
            ConcurrentHashMap<Long, AuditData> fileData = (ConcurrentHashMap<Long, AuditData>) objectStream
                    .readObject();
            for (Map.Entry<Long, AuditData> entry : fileData.entrySet()) {
                if (this.dataMap.size() < (auditConfig.getMaxCacheRow() / 2)) {
                    this.dataMap.putIfAbsent(entry.getKey(), entry.getValue());
                }
                this.sendData(entry.getValue().getDataByte());
                this.sleep();
            }
            objectStream.close();
            inputStream.close();
            file.delete();
        } catch (IOException | ClassNotFoundException e) {
            LOG.error("check audit file error:{}", e.getMessage(), e);
        }
    }

    /**
     * get data map size
     */
    public int getDataMapSize() {
        return this.dataMap.size();
    }

    /**
     * processing return package
     */
    public void onMessageReceived(ChannelHandlerContext ctx, byte[] msg) {
        try {
            // Analyze abnormal events
            AuditApi.BaseCommand baseCommand = AuditApi.BaseCommand.parseFrom(msg);
            // Parse request id
            Long requestId = baseCommand.getAuditReply().getRequestId();
            AuditData data = this.dataMap.get(requestId);
            if (data == null) {
                LOG.error("can not find the request id onMessageReceived: " + requestId);
                return;
            }
            // check resp
            LOG.debug("audit-proxy response code: {}", baseCommand.getAuditReply().getRspCode());
            if (AuditApi.AuditReply.RSP_CODE.SUCCESS.equals(baseCommand.getAuditReply().getRspCode())) {
                this.dataMap.remove(requestId);
                return;
            }
            LOG.error("audit-proxy response code: {}", baseCommand.getAuditReply().getRspCode());

            int resendTimes = data.increaseResendTimes();
            if (resendTimes < SenderGroup.MAX_SEND_TIMES) {
                this.sendData(data.getDataByte());
            }
        } catch (Throwable ex) {
            LOG.error("onMessageReceived exception:{}", ex.getMessage(), ex);
            this.sender.setHasSendError(true);
        }
    }

    /**
     * Handle the packet return exception
     */
    public void onExceptionCaught(ChannelHandlerContext ctx, Throwable e) {
        LOG.error("channel context " + ctx + " occurred exception: ", e);
        try {
            this.sender.setHasSendError(true);
        } catch (Throwable ex) {
            LOG.error("setHasSendError error:{}", ex.getMessage(), ex);
        }
    }

    /**
     * sleep SEND_INTERVAL_MS
     */
    private void sleep() {
        try {
            Thread.sleep(SEND_INTERVAL_MS);
        } catch (Throwable ex) {
            LOG.error("sleep error:{}", ex.getMessage(), ex);
        }
    }

    /***
     * set audit config
     */
    public void setAuditConfig(AuditConfig config) {
        auditConfig = config;
    }

    public void release(Channel channel) {
        this.sender.release(channel);
    }

    /**
     * get dataMap
     * @return the dataMap
     */
    public ConcurrentHashMap<Long, AuditData> getDataMap() {
        return dataMap;
    }

}
