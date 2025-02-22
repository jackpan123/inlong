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

package org.apache.inlong.sort.formats.inlongmsg;

import java.io.Serializable;

/**
 * Interface to handle the failure on parsing InLongMsg data.
 */
public interface FailureHandler extends Serializable {

    /**
     * This method is called when there is a failure occurred while parsing InLongMsg head.
     *
     * @param attribute the attribute which head is parsed from
     * @param exception the thrown exception
     * @throws Exception the exception
     */
    void onParsingHeadFailure(String attribute, Exception exception) throws Exception;

    /**
     * This method is called when there is a failure occurred while parsing InLongMsg body.
     *
     * @param body the body bytes which body is parsed from
     * @param exception the thrown exception
     * @throws Exception the exception
     */
    void onParsingBodyFailure(InLongMsgHead head, byte[] body, Exception exception) throws Exception;

    /**
     * This method is called when there is a failure occurred while converting head and body to row.
     *
     * @param head the head of row
     * @param body the body of row
     * @param exception the thrown exception
     * @throws Exception the exception
     */
    void onConvertingRowFailure(InLongMsgHead head, InLongMsgBody body, Exception exception) throws Exception;
}
