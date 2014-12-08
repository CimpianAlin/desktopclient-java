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

package org.kontalk.client;

import java.util.Collection;
import java.util.Optional;
import java.util.logging.Logger;
import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.RosterListener;
import org.jivesoftware.smack.packet.Presence;
import org.kontalk.model.User;
import org.kontalk.model.UserList;

/**
 *
 * @author Alexander Bikadorov <abiku@cs.tu-berlin.de>
 */
final class KonRosterListener implements RosterListener {
    private final static Logger LOGGER = Logger.getLogger(KonRosterListener.class.getName());

    private final Roster mRoster;
    private final Client mClient;

    KonRosterListener(Roster roster, Client client) {
        mRoster = roster;
        mClient = client;
    }

    @Override
    public void entriesAdded(Collection<String> addresses) {
        if (mRoster == null)
            return;

        UserList userList = UserList.getInstance();
        for (RosterEntry entry: mRoster.getEntries()) {
            if (userList.containsUserWithJID(entry.getUser()))
                continue;
            String name = entry.getName() == null ? "" : entry.getName();
            Optional<User> optNewUser = userList.addUser(entry.getUser(), name);
            if (!optNewUser.isPresent()) {
                LOGGER.warning("can't add user");
                return;
            }
            mClient.sendVCardRequest(optNewUser.get().getJID());
        }
    }

    @Override
    public void entriesUpdated(Collection<String> addresses) {
        LOGGER.info("ignoring entry update in roster");
    }

    @Override
    public void entriesDeleted(Collection<String> addresses) {
        LOGGER.info("ignoring entry deletion in roster");
    }

    @Override
    public void presenceChanged(Presence p) {
        LOGGER.info("got presence change: "+p.getFrom()+" "+p.getXmlns());

        if (p.getFrom() == null || mRoster == null)
            // dunno why this happens
            return;

        String jid = p.getFrom();
        Presence bestPresence = mRoster.getPresence(jid);

        // NOTE: a delay extension is sometimes included, don't know why
        // ignoring mode, always null anyway
        UserList.getInstance().setPresence(bestPresence.getFrom(),
                bestPresence.getType(),
                bestPresence.getStatus());
    }

}
