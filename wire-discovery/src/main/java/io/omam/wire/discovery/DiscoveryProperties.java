/*
Copyright 2018-2020 Cedric Liegeois

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.

    * Neither the name of the copyright holder nor the names of other
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package io.omam.wire.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Properties defined in {@code wire-discovery.properties} that can be overriden by a system property.
 */
public final class DiscoveryProperties {

    /** Google Cast registration type. */
    public static final String REGISTRATION_TYPE;

    /** friendly name attribute key. */
    public static final String FRIENDLY_NAME;

    static {
        try (final InputStream is =
                DiscoveryProperties.class.getClassLoader().getResourceAsStream("wire-discovery.properties")) {
            final Properties props = new Properties();
            props.load(is);
            REGISTRATION_TYPE = stringProp("io.omam.wire.discovery.registrationType", props);
            FRIENDLY_NAME = stringProp("io.omam.wire.discovery.friendlyName", props);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Constructor.
     */
    private DiscoveryProperties() {
        // empty.
    }

    /**
     * Returns the {@code String} corresponding to the given key.
     *
     * @param key property key
     * @param props properties default values
     * @return value
     */
    private static String stringProp(final String key, final Properties props) {
        return System.getProperty(key, props.getProperty(key));
    }

}
