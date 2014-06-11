/*
 *  Kontalk Java client
 *  Copyright (C) 2014 Kontalk Devteam <devteam@kontalk.org>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.model;

import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jivesoftware.smack.packet.Packet;
import org.kontalk.Database;
import org.kontalk.crypto.Coder;

/**
 *
 * @author Alexander Bikadorov <abiku@cs.tu-berlin.de>
 */
public class KonMessage extends ChangeSubject implements Comparable<KonMessage> {
    private final static Logger LOGGER = Logger.getLogger(KonMessage.class.getName());

    public static enum Direction {IN, OUT};
    public static enum Status {IN, //ACKNOWLEDGED, // for incoming
                               PENDING, SENT, RECEIVED, // for outgoing
                               ERROR};

    public final static String TABLE = "messages";
    public final static String CREATE_TABLE = "( " +
            "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "thread_id INTEGER NOT NULL, " +
            "direction INTEGER NOT NULL, " +
            // from or to user
            "user_id INTEGER NOT NULL, " +
            // full jid with ressource
            "jid TEXT NOT NULL, " +
            // optional, but required for receipts
            "xmpp_id TEXT UNIQUE, " +
            // unix time, create/received timestamp
            "date INTEGER NOT NULL, " +
            // enum, server receipt status
            "receipt_status INTEGER NOT NULL, " +
            // receipt id
            "receipt_id TEXT UNIQUE, " +
            // message body, de- or encrypted
            "content BLOB, " +
            // enum, determines if content is encrypted
            "encryption_status INTEGER NOT NULL, " +
            // enum, determines if conent is verified
            // can only tell if signed after encryption attempt
            "signing_status INTEGER, " +
            // encryption and signing errors
            "coder_errors INTEGER, " +

            "FOREIGN KEY (thread_id) REFERENCES thread (_id), " +
            "FOREIGN KEY (user_id) REFERENCES user (_id) " +
            ")";

    private final int mID;
    private final KonThread mThread;
    private final Direction mDir;
    private final User mUser;
    private final String mJID;
    private final String mXMPPID;
    private final Date mDate;
    private Status mReceiptStatus;
    private String mReceiptID;
    private String mText;
    private Coder.Encryption mEncryption;
    private Coder.Signing mSigning;
    private final EnumSet<Coder.Error> mCoderErrors;

    /**
     * Used when sending a new message
     */
    KonMessage(KonThread thread,
            User user,
            String text,
            boolean encrypted) {
        mThread = thread;
        mDir = Direction.OUT;
        mUser = user;
        mJID = user.getJID();
        mXMPPID = Packet.nextID();
        mDate = new Date(); // "now"
        mReceiptStatus = Status.PENDING;
        mReceiptID = null;
        mText = text;
        // if we want encryption we also want signing, doesn't hurt
        if (encrypted) {
            mEncryption = Coder.Encryption.ENCRYPTED;
            mSigning = Coder.Signing.SIGNED;
        } else {
            mEncryption = Coder.Encryption.NOT;
            mSigning = Coder.Signing.NOT;
        }
        mCoderErrors = EnumSet.noneOf(Coder.Error.class);

        mID = this.insert();
    }

    /**
     * Used when receiving a new message
     */
    KonMessage(KonThread thread,
            User user,
            String jid,
            String xmppID,
            Date date,
            String receiptID,
            String text,
            boolean encrypted) {
        mThread = thread;
        mDir = Direction.IN;
        mUser = user;
        mJID = jid;
        mXMPPID = xmppID;
        mDate = date;
        mReceiptStatus = Status.IN;
        mReceiptID = receiptID;
        mText = text;
        if (encrypted)
            mEncryption = Coder.Encryption.ENCRYPTED;
        else
            mEncryption = Coder.Encryption.NOT;
        // if encrypted we don't know yet
        mSigning = encrypted ? null : Coder.Signing.NOT ;
        mCoderErrors = EnumSet.noneOf(Coder.Error.class);

        mID = this.insert();
    }

    /**
     * Used when loading from database
     */
    KonMessage(int id,
            KonThread thread,
            Direction dir,
            User user,
            String jid,
            String xmppID,
            Date date,
            Status status,
            String receiptID,
            String text,
            Coder.Encryption encryption,
            Coder.Signing signing,
            EnumSet<Coder.Error> coderErrors) {
        mID = id;
        mThread = thread;
        mDir = dir;
        mUser = user;
        mJID = jid;
        mXMPPID = xmppID;
        mDate = date;
        mReceiptStatus = status;
        mReceiptID = receiptID;
        mText = text;
        mEncryption = encryption;
        mSigning = signing;
        mCoderErrors = coderErrors;
    }

    public int getID() {
        return mID;
    }

    public KonThread getThread() {
        return mThread;
    }

    public Direction getDir() {
        return mDir;
    }

    public User getUser() {
        return mUser;
    }

    public String getJID() {
        return mJID;
    }

    public String getXMPPID() {
        return mXMPPID;
    }

    public Date getDate() {
        return mDate;
    }

    public Status getReceiptStatus() {
        return mReceiptStatus;
    }

    String getReceiptID() {
        return mReceiptID;
    }

    public String getText() {
        return mText;
    }

    // TODO
    public boolean isEncrypted() {
        return mEncryption == Coder.Encryption.ENCRYPTED ||
                mEncryption == Coder.Encryption.DECRYPTED;
    }

    public Coder.Encryption getEncryption() {
        return mEncryption;
    }

    public void setDecryptedText(String text) {
        assert mEncryption == Coder.Encryption.ENCRYPTED;
        mText = text;
        mEncryption = Coder.Encryption.DECRYPTED;
        this.save();
    }

    public Coder.Signing getSigning() {
        return mSigning;
    }

    public void setSigning(Coder.Signing signing) {
        if (signing == mSigning)
            return;
        if (signing == Coder.Signing.NOT)
            assert mSigning == null;
        if (signing == Coder.Signing.SIGNED)
            assert mSigning == null;
        if (signing == Coder.Signing.VERIFIED)
            assert mSigning == Coder.Signing.SIGNED;
        mSigning = signing;
    }

    public void addSecurityError(Coder.Error error) {
        mCoderErrors.add(error);
        this.save();
    }

    public EnumSet<Coder.Error> getSecurityErrors() {
        // better return a copy
        return mCoderErrors.clone();
    }

    public boolean hasSecurityError(Coder.Error error) {
        return mCoderErrors.contains(error);
    }

    public void resetSecurityErrors() {
        mCoderErrors.clear();
        this.save();
    }

    @Override
    public int compareTo(KonMessage o) {
        int idComp = Integer.compare(this.mID, o.mID);
        int dateComp = mDate.compareTo(o.getDate());
        return (idComp == 0 || dateComp == 0) ? idComp : dateComp;
    }

    void updateBySentReceipt(String receiptID) {
        assert mDir == Direction.OUT;
        assert mReceiptStatus == Status.PENDING;
        assert mReceiptID == null;
        mReceiptID = receiptID;
        mReceiptStatus = Status.SENT;
        this.save();
        this.changed();
    }

    void updateByReceivedReceipt() {
        assert mDir == Direction.OUT;
        assert mReceiptStatus == Status.SENT;
        assert mReceiptID != null;
        mReceiptStatus = Status.RECEIVED;
        this.save();
        this.changed();
    }

    private int insert(){
        Database db = Database.getInstance();
        List<Object> values = new LinkedList();
        values.add(mThread.getID());
        values.add(mDir);
        values.add(mUser.getID());
        values.add(mJID);
        values.add(mXMPPID);
        values.add(mDate);
        values.add(mReceiptStatus);
        values.add(mReceiptID);
        values.add(mText);
        values.add(mEncryption);
        values.add(mSigning);
        values.add(mCoderErrors);

        int id = db.execInsert(TABLE, values);
        if (id < 1) {
            LOGGER.log(Level.WARNING, "couldn't insert message");
        }
        return id;
    }

    private void save() {
       Database db = Database.getInstance();
       Map<String, Object> set = new HashMap();
       set.put("xmpp_id", mXMPPID);
       set.put("receipt_status", mReceiptStatus);
       set.put("receipt_id", mReceiptID);
       set.put("content", mText);
       set.put("encryption_status", mEncryption);
       set.put("signing_status", mSigning);
       set.put("coder_errors", mCoderErrors);
       db.execUpdate(TABLE, set, mID);
    }
}
