/*
 *  Copyright 2000-2023 JetBrains s.r.o.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package jetbrains.buildServer.notification.slackNotifier.notification

import jetbrains.buildServer.serverSide.SRunningBuild
import org.springframework.stereotype.Service

@Service
class PlainAdHocMessageBuilder(
    private val detailsFormatter: DetailsFormatter
): AdHocMessageBuilder {
    override fun buildRelatedNotification(
        build: SRunningBuild,
        message: String
    ): MessagePayload {
        val payloadBuilder = MessagePayloadBuilder()

        payloadBuilder.contextBlock {
            add(
                "Sent by ${detailsFormatter.buildUrl(build)}"
            )
        }
        payloadBuilder.textBlock { add(message) }

        return payloadBuilder.build()
    }
}