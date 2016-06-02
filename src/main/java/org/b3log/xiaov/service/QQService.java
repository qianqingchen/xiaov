/*
 * Copyright (c) 2012-2016, b3log.org & hacpai.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.b3log.xiaov.service;

import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.service.annotation.Service;
import org.b3log.latke.servlet.HTTPRequestMethod;
import org.b3log.latke.urlfetch.HTTPRequest;
import org.b3log.latke.urlfetch.HTTPResponse;
import org.b3log.latke.urlfetch.URLFetchService;
import org.b3log.latke.urlfetch.URLFetchServiceFactory;
import org.b3log.latke.util.Strings;
import org.b3log.xiaov.util.XiaoVs;

import com.scienjus.smartqq.callback.MessageCallback;
import com.scienjus.smartqq.client.SmartQQClient;
import com.scienjus.smartqq.model.DiscussMessage;
import com.scienjus.smartqq.model.Group;
import com.scienjus.smartqq.model.GroupMessage;
import com.scienjus.smartqq.model.Message;

/**
 * QQ service.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.1.0.0, May 30, 2016
 * @since 1.0.0
 */
@Service
public class QQService {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(QQService.class.getName());

    /**
     * QQ groups.
     *
     * &lt;groupId, group&gt;
     */
    private final Map<Long, Group> QQ_GROUPS = new HashMap<Long, Group>();

    /**
     * QQ client.
     */
    private SmartQQClient qqClient;

    /**
     * Turing query service.
     */
    @Inject
    private TuringQueryService turingQueryService;
    
    @Inject
    private BaiduQueryService baiduQueryService;
    
    private static final String QQ_BOT_TYPE = XiaoVs.getString("qq.bot.type");
    
    private static final String QQ_BOT_NAME = XiaoVs.getString("qq.bot.name");
    
    //是否强制回复
    private static final String IS_FORCE_ANSWER = XiaoVs.getString("qq.bot.isForceAnswer");
    
    private static final String defaultGroupsConf = XiaoVs.getString("qq.bot.pushGroups");
    /**
     * URL fetch service.
     */
    private static final URLFetchService URL_FETCH_SVC = URLFetchServiceFactory.getURLFetchService();
    
    private List<String> groupNames = new ArrayList<String>();

    /**
     * Initializes QQ client.
     */
    public void initQQClient() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                qqClient = new SmartQQClient(new MessageCallback() {
                    @Override
                    public void onMessage(final Message message) {
                        final String content = message.getContent();

                        final String key = XiaoVs.getString("qq.bot.key");
                        if (!StringUtils.startsWith(content, key)) {
                            return;
                        }

                        final String msg = StringUtils.substringAfter(content, key);
                        LOGGER.info("Received admin message: " + msg);
                        sendToQQGroups(msg);
                    }
                    
                    @Override
                    public void onGroupMessage(final GroupMessage message) {
                        final long groupId = message.getGroupId();
                        if (QQ_GROUPS.isEmpty()) {
                            return;
                        }
                        if (QQ_GROUPS.get(groupId) == null) {
                        	return;
                        }
                        if (!groupNames.contains(QQ_GROUPS.get(groupId).getName())) {
                        	return;
                        }
                        final String content = message.getContent();

                        // Push to forum
                        String qqMsg = content.replaceAll("\\[\"face\",[0-9]+\\]", "");
                        if (StringUtils.isNotBlank(qqMsg)) {
                            final String userName = Long.toHexString(message.getUserId());
                            qqMsg = "<p>" + qqMsg + "</p>";
                            sendToForum(qqMsg, userName);
                        }
                        String msg = "";
                        if (StringUtils.contains(content, XiaoVs.getString("qq.bot.name"))
                                || (StringUtils.length(content) > 6
                                && (StringUtils.contains(content, "?") || StringUtils.contains(content, "？") || StringUtils.contains(content, "问")))) {
                            msg = answer(content);
                            LOGGER.info(content + ": " + msg);
                        }
                        else if (StringUtils.length(content) > 0 && "1".equals(IS_FORCE_ANSWER)){
                        	msg = answerWithoutName(QQ_BOT_NAME + content);
                            LOGGER.info(content + ": " + msg);
                        }
                        if (StringUtils.isNotBlank(msg)) {
                        	sendMessageToGroup(groupId, msg);
                        }
                    }

                    private String answer(final String content) {
                        String keyword = "";
                        String[] keywords = StringUtils.split(XiaoVs.getString("bot.follow.keywords"), ",");
                        keywords = Strings.trimAll(keywords);
                        for (final String kw : keywords) {
                            if (StringUtils.containsIgnoreCase(content, kw)) {
                                keyword = kw;
                                break;
                            }
                        }
                        String ret = "";
                        if (StringUtils.isNotBlank(keyword)) {
                                ret = "我好像迷路了！";
                        } else if (StringUtils.contains(content, QQ_BOT_NAME) && QQ_BOT_TYPE.equals("1")) {
                            ret = turingQueryService.chat("Vanessa", content);
                        }
                        else if (StringUtils.contains(content, QQ_BOT_NAME) && QQ_BOT_TYPE.equals("2")) {
                        	ret = baiduQueryService.chat(content.replace(QQ_BOT_NAME, "小度"));
                        }
                        return ret;
                    }
                    
                    //强制回复，不需要输入机器人名
                    private String answerWithoutName(final String content) {
                        String keyword = "";
                        String[] keywords = StringUtils.split(XiaoVs.getString("bot.follow.keywords"), ",");
                        keywords = Strings.trimAll(keywords);
                        for (final String kw : keywords) {
                            if (StringUtils.containsIgnoreCase(content, kw)) {
                                keyword = kw;
                                break;
                            }
                        }
                        String ret = "";
                        if (StringUtils.isNotBlank(keyword)) {
                                ret = "我好像迷路了！";
                        } else if (StringUtils.contains(content, QQ_BOT_NAME) && QQ_BOT_TYPE.equals("1")) {
                            ret = turingQueryService.chat("Vanessa", content.substring(QQ_BOT_NAME.length()));
                        }
                        else if (StringUtils.contains(content, QQ_BOT_NAME) && QQ_BOT_TYPE.equals("2")) {
                        	ret = baiduQueryService.chat(content.substring(QQ_BOT_NAME.length()));
                        }
                        return ret;
                    }
                    
                    @Override
                    public void onDiscussMessage(final DiscussMessage message) {
                    }
                });

                // Load groups
                final List<Group> groups = qqClient.getGroupList();
                for (final Group group : groups) {
                    QQ_GROUPS.put(group.getId(), group);

                    LOGGER.info(group.getName() + ": " + group.getId());
                }
            }
        }).start();
    }

    private void sendToForum(final String msg, final String user) {
        final String forumAPI = XiaoVs.getString("forum.api");
        final String forumKey = XiaoVs.getString("forum.key");

        final HTTPRequest request = new HTTPRequest();
        request.setRequestMethod(HTTPRequestMethod.POST);

        try {
            request.setURL(new URL(forumAPI));

            final String body = "key=" + URLEncoder.encode(forumKey, "UTF-8")
                    + "&msg=" + URLEncoder.encode(msg, "UTF-8")
                    + "&user=" + URLEncoder.encode(user, "UTF-8");
            request.setPayload(body.getBytes("UTF-8"));

            final HTTPResponse response = URL_FETCH_SVC.fetch(request);
            final int sc = response.getResponseCode();
            if (HttpServletResponse.SC_OK != sc) {
                LOGGER.warn("Sends message to Forum status code is [" + sc + "]");
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Sends message to Forum failed: " + e.getMessage());
        }
    }

    /**
     * Closes QQ client.
     */
    public void closeQQClient() {
        if (null == qqClient) {
            return;
        }

        try {
            qqClient.close();
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Closes QQ client failed", e);
        }
    }

    /**
     * Sends the specified article to QQ groups.
     *
     * @param msg the specified message
     */
    public void sendToPushQQGroups(final String msg) {
        if (StringUtils.isBlank(defaultGroupsConf)) {
            return;
        }
        final String[] groups = defaultGroupsConf.split(",");
        for (final Map.Entry<Long, Group> entry : QQ_GROUPS.entrySet()) {
            final Group group = entry.getValue();
            final String name = group.getName();

            if (Strings.contains(name, groups)) {
                sendMessageToGroup(group.getId(), msg);
            }
        }
    }
    
    /**
     * Sends the specified article to QQ groups.
     *
     * @param msg the specified message
     */
    public void sendToPushQQGroups(final Long groupId, final String msg) {
        if (StringUtils.isBlank(defaultGroupsConf)) {
            return;
        }
        final String[] groups = defaultGroupsConf.split(",");
        for (final Map.Entry<Long, Group> entry : QQ_GROUPS.entrySet()) {
            final Group group = entry.getValue();
            final String name = group.getName();

            if (Strings.contains(name, groups)) {
                sendMessageToGroup(group.getId(), msg);
            }
        }
    }

    private void sendToQQGroups(final String msg) {
        for (final Map.Entry<Long, Group> entry : QQ_GROUPS.entrySet()) {
            final Group group = entry.getValue();
            sendMessageToGroup(group.getId(), msg);
        }
    }

    private void sendMessageToGroup(final Long groupId, final String msg) {
        final Group group = QQ_GROUPS.get(groupId);

        if (null == group) {
            // Reload groups

            final List<Group> groups = qqClient.getGroupList();
            QQ_GROUPS.clear();

            for (final Group g : groups) {
                QQ_GROUPS.put(g.getId(), g);

                LOGGER.info(g.getName() + ": " + g.getId());
            }
        }

        LOGGER.info("Pushing [msg=" + msg + "] to QQ qun [" + group.getName() + "]");
        qqClient.sendMessageToGroup(groupId, msg);
    }

	public QQService() {
		if (!StringUtils.isBlank(defaultGroupsConf)) {
			String[] groups = defaultGroupsConf.split(",");
			groupNames = Arrays.asList(groups);
        }
	}
    
}
