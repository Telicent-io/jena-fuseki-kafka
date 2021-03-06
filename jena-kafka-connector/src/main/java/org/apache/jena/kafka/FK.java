/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jena.kafka;

import java.util.HashMap;
import java.util.Map;

import org.apache.jena.atlas.lib.Bytes;
import org.apache.kafka.common.header.Headers;

public class FK {

    /**
     * Kafka headers to a Map. If there are multiple headers with the same key name,
     * only the last header value goes in the map.
     */
    public static Map<String, String> headerToMap(Headers headers) {
        Map<String, String> map = new HashMap<>();
        headers.forEach(header->{
            String hName = header.key();
            String hValue = Bytes.bytes2string(header.value());
            map.put(hName,  hValue);
        });
        return map;
    }

//    /**
//     * Print incoming.
//     */
//    public static void print(ActionFK action) {
//        System.out.println("== Topic: "+action.getTopic());
//        System.out.println(action.getHeaders());
//        String dataStr = IO.readWholeFileAsUTF8(action.getBytes());
//        System.out.print(dataStr);
//        if ( ! dataStr.endsWith("\n") )
//            System.out.println();
//        System.out.println("--");
//    }
}
