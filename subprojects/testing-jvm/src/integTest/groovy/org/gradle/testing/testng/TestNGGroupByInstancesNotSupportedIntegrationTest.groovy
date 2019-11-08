/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.testing.testng

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.FailsWithInstantExecution

public class TestNGGroupByInstancesNotSupportedIntegrationTest extends AbstractIntegrationSpec {

    @FailsWithInstantExecution
    def "run tests using TestNG version not supporting groupByInstances"() {
        given:
        buildFile << """
            apply plugin: 'java'
            ${mavenCentralRepository()}
            dependencies { testImplementation 'org.testng:testng:6.0.1' }
            test { useTestNG { groupByInstances true } }
        """

        file("src/test/java/SimpleTest.java") << """
            import org.testng.annotations.Test;

            public class SimpleTest {

                @Test
                public void test() {}
           }
        """

        when:
        fails "test"

        then:
        failure.assertHasCause("Grouping tests by instances is not supported by this version of TestNG.")
    }
}
