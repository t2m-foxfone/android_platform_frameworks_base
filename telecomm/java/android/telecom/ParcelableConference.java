/*
 * Copyright 2014, The Android Open Source Project
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

package android.telecom;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

import com.android.internal.telecom.IVideoProvider;

/**
 * A parcelable representation of a conference connection.
 * @hide
 */
public final class ParcelableConference implements Parcelable {

    private PhoneAccountHandle mPhoneAccount;
    private int mState;
    private int mCapabilities;
    private List<String> mConnectionIds;
    private final IVideoProvider mVideoProvider;
    private final int mVideoState;


    public ParcelableConference(
            PhoneAccountHandle phoneAccount,
            int state,
            int capabilities,
            List<String> connectionIds,
            IVideoProvider videoProvider,
            int videoState) {
        mPhoneAccount = phoneAccount;
        mState = state;
        mCapabilities = capabilities;
        mConnectionIds = connectionIds;
        mVideoProvider = videoProvider;
        mVideoState = videoState;
    }

    @Override
    public String toString() {
        return (new StringBuffer())
                .append("account: ")
                .append(mPhoneAccount)
                .append(", state: ")
                .append(Connection.stateToString(mState))
                .append(", capabilities: ")
                .append(PhoneCapabilities.toString(mCapabilities))
                .append(", children: ")
                .append(mConnectionIds)
                .append(", VideoState: ")
                .append(mVideoState)
                .append(", VideoProvider: ")
                .append(mVideoProvider)
                .toString();
    }

    public PhoneAccountHandle getPhoneAccount() {
        return mPhoneAccount;
    }

    public int getState() {
        return mState;
    }

    public int getCapabilities() {
        return mCapabilities;
    }

    public List<String> getConnectionIds() {
        return mConnectionIds;
    }

    public IVideoProvider getVideoProvider() {
        return mVideoProvider;
    }

    public int getVideoState() {
        return mVideoState;
    }

    public static final Parcelable.Creator<ParcelableConference> CREATOR =
            new Parcelable.Creator<ParcelableConference> () {
        @Override
        public ParcelableConference createFromParcel(Parcel source) {
            ClassLoader classLoader = ParcelableConference.class.getClassLoader();
            PhoneAccountHandle phoneAccount = source.readParcelable(classLoader);
            int state = source.readInt();
            int capabilities = source.readInt();
            List<String> connectionIds = new ArrayList<>(2);
            source.readList(connectionIds, classLoader);
            IVideoProvider videoCallProvider =
                    IVideoProvider.Stub.asInterface(source.readStrongBinder());
            int videoState = source.readInt();

            return new ParcelableConference(phoneAccount, state, capabilities, connectionIds,
                    videoCallProvider, videoState);
        }

        @Override
        public ParcelableConference[] newArray(int size) {
            return new ParcelableConference[size];
        }
    };

    /** {@inheritDoc} */
    @Override
    public int describeContents() {
        return 0;
    }

    /** Writes ParcelableConference object into a Parcel. */
    @Override
    public void writeToParcel(Parcel destination, int flags) {
        destination.writeParcelable(mPhoneAccount, 0);
        destination.writeInt(mState);
        destination.writeInt(mCapabilities);
        destination.writeList(mConnectionIds);
        destination.writeStrongBinder(
                mVideoProvider != null ? mVideoProvider.asBinder() : null);
        destination.writeInt(mVideoState);
    }
}
