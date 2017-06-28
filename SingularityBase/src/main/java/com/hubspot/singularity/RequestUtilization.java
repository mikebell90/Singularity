package com.hubspot.singularity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class RequestUtilization {
  private final String requestId;
  private final String deployId;
  private long memBytesUsed = 0;
  private long memBytesReserved = 0;
  private double cpuUsed = 0;
  private double cpuReserved = 0;
  private int numTasks = 0;

  @JsonCreator
  public RequestUtilization(@JsonProperty("requestId") String requestId,
                            @JsonProperty("deployId") String deployId) {
    this.requestId = requestId;
    this.deployId = deployId;
  }

  public RequestUtilization addMemBytesUsed(long memBytes) {
    this.memBytesUsed += memBytes;
    return this;
  }

  public RequestUtilization addMemBytesReserved(long memBytes) {
    this.memBytesReserved += memBytes;
    return this;
  }

  public RequestUtilization addCpuUsed(double cpu) {
    this.cpuUsed += cpu;
    return this;
  }

  public RequestUtilization addCpuReserved(double cpu) {
    this.cpuReserved += cpu;
    return this;
  }

  public RequestUtilization incrementTaskCount() {
    this.numTasks++;
    return this;
  }

  public long getMemBytesUsed() {
    return memBytesUsed;
  }

  public long getMemBytesReserved() {
    return memBytesReserved;
  }

  public double getCpuUsed() {
    return cpuUsed;
  }

  public double getCpuReserved() {
    return cpuReserved;
  }

  public int getNumTasks() {
    return numTasks;
  }

  @JsonIgnore
  public double getAvgMemBytesUsed() {
    return memBytesUsed / (double) numTasks;
  }

  @JsonIgnore
  public double getAvgCpuUsed() {
    return cpuUsed / (double) numTasks;
  }

  public String getDeployId() {
    return deployId;
  }

  public String getRequestId() {
    return requestId;
  }

  @Override
  public String toString() {
    return "RequestUtilization{" +
        "requestId=" + requestId +
        ", deployId=" + deployId +
        ", memBytesUsed=" + memBytesUsed +
        ", memBytesReserved=" + memBytesReserved +
        ", cpuUsed=" + cpuUsed +
        ", cpuReserved=" + cpuReserved +
        ", numTasks=" + numTasks +
        '}';
  }
}
