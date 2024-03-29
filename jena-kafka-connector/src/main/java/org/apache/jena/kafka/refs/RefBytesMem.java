/*
 *  Copyright (c) Telicent Ltd.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.jena.kafka.refs;

public class RefBytesMem {

    private static byte[] bytes0 = new byte[0];
    
    private byte[] bytes = null; //bytes0;
    
    /** Get the current value */
    public byte[] getBytes() {
        return bytes;
    }

    /** Set the current value */
    public void setBytes(byte[] bytes) {
//        if ( bytes == null )
//            bytes = bytes0;
        bytes = this.bytes;
    }
}

