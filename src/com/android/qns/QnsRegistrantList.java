/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.qns;

import android.os.Handler;

import java.util.ArrayList;

/** @hide */
public class QnsRegistrantList {
    protected final ArrayList<QnsRegistrant> mRegistrants;

    /** constructor */
    public QnsRegistrantList() {
        mRegistrants = new ArrayList<>();
    }

    /**
     * Add each element for the registrant.
     *
     * @param h handler
     * @param what message to be delivered
     * @param obj object
     */
    public synchronized void add(Handler h, int what, Object obj) {
        add(new QnsRegistrant(h, what, obj));
    }

    /**
     * Add each element for the registrant. does not allow duplicated handler
     *
     * @param h handler
     * @param what message to be delivered
     * @param obj object
     */
    public synchronized void addUnique(Handler h, int what, Object obj) {
        // if the handler is already in the registrant list, remove it
        remove(h);
        add(new QnsRegistrant(h, what, obj));
    }

    /**
     * Add the registrant.
     *
     * @param r registrant.
     */
    public synchronized void add(QnsRegistrant r) {
        removeCleared();
        mRegistrants.add(r);
    }

    /** Remove cleared registrant in list */
    public synchronized void removeCleared() {
        for (int i = mRegistrants.size() - 1; i >= 0; i--) {
            QnsRegistrant r = mRegistrants.get(i);

            if (r.mRefH == null) {
                mRegistrants.remove(i);
            }
        }
    }

    /** Remove all registrant */
    public synchronized void removeAll() {
        mRegistrants.clear();
    }

    /**
     * Returns size of Registrant.
     *
     * @return size
     */
    public synchronized int size() {
        return mRegistrants.size();
    }

    /**
     * Returns Object from the list
     *
     * @param index index
     * @return Object
     */
    public synchronized Object get(int index) {
        return mRegistrants.get(index);
    }

    private synchronized void internalNotifyRegistrants(Object result, Throwable exception) {
        for (QnsRegistrant registrant : mRegistrants) {
            registrant.internalNotifyRegistrant(result, exception);
        }
    }

    /** notify registrant */
    public void notifyRegistrants() {
        internalNotifyRegistrants(null, null);
    }

    /**
     * notify registrant with given object
     *
     * @param result object
     */
    public void notifyResult(Object result) {
        internalNotifyRegistrants(result, null);
    }

    /**
     * notify registrant
     *
     * @param ar QnsAsyncResult to be notified
     */
    public void notifyRegistrants(QnsAsyncResult ar) {
        internalNotifyRegistrants(ar.result, ar.exception);
    }

    /**
     * remove handler in list
     *
     * @param h handler
     */
    public synchronized void remove(Handler h) {
        for (QnsRegistrant r : mRegistrants) {
            Handler rh;

            rh = r.getHandler();

            /* Clean up both the requested registrant and
             * any now-collected registrants
             */
            if (rh == null || rh == h) {
                r.clear();
            }
        }

        removeCleared();
    }
}
