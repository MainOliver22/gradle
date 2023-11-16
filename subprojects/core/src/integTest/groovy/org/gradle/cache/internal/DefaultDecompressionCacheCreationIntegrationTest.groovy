/*
 * Copyright 2023 the original author or authors.
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

package org.gradle.cache.internal

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.TestResources
import org.gradle.internal.hash.DefaultFileHasher
import org.gradle.internal.hash.DefaultStreamHasher
import org.gradle.internal.hash.FileHasher
import org.gradle.util.GradleVersion
import org.gradle.util.internal.GFileUtils
import org.junit.Rule
import spock.lang.Ignore

class DefaultDecompressionCacheCreationIntegrationTest extends AbstractIntegrationSpec {
    @Rule
    public final TestResources resources = new TestResources(testDirectoryProvider)

    def setup() {
        resources.maybeCopy("${DefaultDecompressionCacheCreationIntegrationTest.class.simpleName}/zip")
    }

    def "file with same name as cache content dir already exists causes creation failure"() {
        given:
        File expandedContent = file("build/tmp/.cache/expanded")
        GFileUtils.parentMkdirs(expandedContent)
        expandedContent.createNewFile()

        buildFile << addUnzipAndVerifyTask()

        expect:
        fails "verify"
        failure.assertHasCause("Cannot create directory '${expandedContent}' as it already exists, but is not a directory")
    }

    def "file with same name as cache lock dir already exists causes creation failure"() {
        given:
        File expandedLock = file(".gradle/expanded/${temporaryFolder.getTestDirectory().getName()}/${GradleVersion.current().version}")
        GFileUtils.parentMkdirs(expandedLock)
        expandedLock.createNewFile()

        buildFile << addUnzipAndVerifyTask()

        expect:
        fails "verify"
        failure.assertHasCause("Cannot create directory '${expandedLock}' as it already exists, but is not a directory")
        file("build/tmp/.cache/expanded").assertDoesNotExist() // Cache content shouldn't be created if we can't create the lock
    }

    // TODO: This seems like a general caching problem, not specific to decompression.  Shouldn't this build fail somehow?
    @Ignore
    def "pre-existing content in cache dir with same hash is okay"() {
        FileHasher hasher = new DefaultFileHasher(new DefaultStreamHasher())
        String hash = hasher.hash(file("hello.zip"))

        given:
        File preExpandedContent = file("build/tmp/.cache/expanded/zip_${hash}/Test.txt")
        GFileUtils.parentMkdirs(preExpandedContent)
        GFileUtils.touch(preExpandedContent)
        preExpandedContent << "some incorrect pre-existing pre-expanded content"

        buildFile << addUnzipAndVerifyTask()

        expect:
        succeeds "verify"
        File afterExpansion = file("build/tmp/.cache/expanded/zip_${hash}/Test.txt")

        // This is the expected contents of the actual zip file that we expect to find via the cache, instead we get the incorrect content
        afterExpansion.text == """Test zip file
Zip me
Zip me now!
"""
    }

    private String addUnzipAndVerifyTask(File lockFile = file(".gradle/expanded/${temporaryFolder.getTestDirectory().getName()}/${GradleVersion.current().version}/expanded.lock")) {
        return """
            tasks.register('verify') {
                doLast {
                    zipTree(file("hello.zip")).files

                    def cacheDir = file("build/tmp/.cache/expanded")
                    assert cacheDir.list().size() == 1
                    assert cacheDir.listFiles()[0].name.startsWith('zip_')

                    def lockFile = file("${lockFile}")
                    assert lockFile.exists()
                }
            }
        """
    }
}
