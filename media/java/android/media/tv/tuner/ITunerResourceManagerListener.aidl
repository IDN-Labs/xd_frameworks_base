/*
 * Copyright 2020 The Android Open Source Project
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

package android.media.tv.tuner;

/**
 * Interface to receive callbacks from ITunerResourceManager.
 *
 * @hide
 */
oneway interface ITunerResourceManagerListener {
    /*
     * TRM invokes this method when the client's resources need to be reclaimed.
     *
     * <p>This method is implemented in Tuner Framework to take the reclaiming
     * actions. It's a synchonized call. TRM would wait on the call to finish
     * then grant the resource.
     */
    void onResourcesReclaim();
}