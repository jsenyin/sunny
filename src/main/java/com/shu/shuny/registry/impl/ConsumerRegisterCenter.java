package com.shu.shuny.registry.impl;

import com.google.common.collect.Maps;
import com.shu.shuny.model.ProviderServiceMeta;
import org.apache.commons.collections.MapUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

/**
 * @Author:shucq
 * @Description:
 * @Date 2019/10/2 19:14
 */
public abstract class ConsumerRegisterCenter {
    //服务端ZK服务元信息,选择服务(第一次直接从ZK拉取,后续由ZK的监听机制主动更新)
    private static final Map<String, List<ProviderServiceMeta>> serviceMetaDataMapConsume = Maps.newConcurrentMap();
    public static String INVOKER_TYPE = "consumer";


    public void initProviderMap(String serviceKey, String version) {
        if (MapUtils.isEmpty(serviceMetaDataMapConsume)) {
            serviceMetaDataMapConsume.putAll(fetchOrUpdateServiceMetaData(serviceKey, version));
        }
    }

    protected abstract Map<String, List<ProviderServiceMeta>> fetchOrUpdateServiceMetaData(String serviceKey,
        String version);

    protected void refreshServiceMetaDataMap(List<String> serviceIpList) {
        serviceMetaDataMapConsume.clear();
        if (serviceIpList == null) {
            return;
        }
        ConcurrentHashMap<String, List<ProviderServiceMeta>> result =
            serviceMetaDataMapConsume.values().stream().flatMap(e -> e.stream())
                .filter(e -> serviceIpList.contains(e.getServerIp()))
                .collect(groupingBy(e -> e.getServiceInterface().getName(), ConcurrentHashMap::new, toList()));
        serviceMetaDataMapConsume.putAll(result);
    }

    public Map<String, List<ProviderServiceMeta>> getServiceMetaDataMapWithConsume() {
        return serviceMetaDataMapConsume;
    }
}