/*
 *  Kontalk Java client
 *  Copyright (C) 2016 Kontalk Devteam <devteam@kontalk.org>
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

package org.kontalk.view;

import javax.swing.Box;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Optional;

import com.alee.extended.panel.GroupPanel;
import com.alee.extended.panel.GroupingType;
import com.alee.laf.label.WebLabel;
import com.alee.laf.menu.WebMenuItem;
import com.alee.laf.menu.WebPopupMenu;
import org.kontalk.model.Contact;
import org.kontalk.model.chat.Chat;
import org.kontalk.model.chat.ChatList;
import org.kontalk.model.chat.GroupChat;
import org.kontalk.model.chat.Member;
import org.kontalk.model.chat.SingleChat;
import org.kontalk.model.message.KonMessage;
import org.kontalk.persistence.Config;
import org.kontalk.util.Tr;
import org.kontalk.view.ChatListView.ChatItem;

/**
 * Show a brief list of all chats.
 * @author Alexander Bikadorov {@literal <bikaejkb@mail.tu-berlin.de>}
 */
final class ChatListView extends ListView<ChatItem, Chat> {

    private final ChatList mChatList;

    ChatListView(final View view, ChatList chatList) {
        super(view, true);
        mChatList = chatList;

        this.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        this.updateOnEDT(null);
    }

    @Override
    protected void updateOnEDT(Object arg) {
        if (arg == null || arg == ChatList.ViewChange.MODIFIED)
            this.sync(mChatList.getAll());
    }

    @Override
    protected ChatItem newItem(Chat value) {
        return new ChatItem(value);
    }

    void selectLastChat() {
        int i = Config.getInstance().getInt(Config.VIEW_SELECTED_CHAT);
        if (i < 0) i = 0;
        this.setSelectedItem(i);
    }

    void save() {
        Config.getInstance().setProperty(Config.VIEW_SELECTED_CHAT,
                this.getSelectedRow());
    }

    private void deleteChat(ChatItem item) {
        if (!item.mValue.getMessages().isEmpty()) {
            String text = Tr.tr("Permanently delete all messages in this chat?");
            if (item.mValue.isGroupChat() && item.mValue.isValid())
                text += "\n\n"+Tr.tr("You will automatically leave this group.");
            if (!Utils.confirmDeletion(this, text))
                return;
        }
        ChatItem chatItem = this.getSelectedItem();
        mView.getControl().deleteChat(chatItem.mValue);
    }

    @Override
    protected void selectionChanged(Optional<Chat> value) {
        mView.onChatSelectionChanged(value);
    }

    @Override
    protected WebPopupMenu rightClickMenu(ChatItem item) {
        WebPopupMenu menu = new WebPopupMenu();

        Chat chat = item.mValue;
        if (chat instanceof SingleChat) {
            final Contact contact = ((SingleChat) chat).getMember().getContact();
            if (!contact.isDeleted()) {
                WebMenuItem editItem = new WebMenuItem(Tr.tr("Edit Contact"));
                editItem.setToolTipText(Tr.tr("Edit contact settings"));
                editItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent event) {
                        mView.showContactDetails(contact);
                    }
                });
                menu.add(editItem);
            }
        }

        WebMenuItem deleteItem = new WebMenuItem(Tr.tr("Delete Chat"));
        deleteItem.setToolTipText(Tr.tr("Delete this chat"));
        deleteItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                ChatListView.this.deleteChat(ChatListView.this.getSelectedItem());
            }
        });
        menu.add(deleteItem);

        return menu;
    }

    @Override
    protected void onRenameEvent() {
        Chat chat = this.getSelectedValue().orElse(null);
        if (chat instanceof SingleChat) {
            mView.requestRenameFocus(((SingleChat) chat).getMember().getContact());
            return;
        }

        if (chat instanceof GroupChat) {
            // TODO
        }
    }

    protected final class ChatItem extends ListView<ChatItem, Chat>.TableItem {

        private final ComponentUtils.AvatarImage mAvatar;
        private final WebLabel mTitleLabel;
        private final WebLabel mStatusLabel;
        private final WebLabel mChatStateLabel;
        private Color mBackground;

        ChatItem(Chat chat) {
            super(chat);

            this.setLayout(new BorderLayout(View.GAP_DEFAULT, 0));
            this.setMargin(View.MARGIN_DEFAULT);

            mAvatar = new ComponentUtils.AvatarImage(View.AVATAR_LIST_SIZE);
            this.add(mAvatar, BorderLayout.WEST);

            mTitleLabel = new WebLabel();
            mTitleLabel.setFontSize(View.FONT_SIZE_BIG);
            mTitleLabel.setDrawShade(true);
            if (mValue.isGroupChat())
                    mTitleLabel.setForeground(View.DARK_GREEN);

            mStatusLabel = new WebLabel();
            mStatusLabel.setForeground(Color.GRAY);
            mStatusLabel.setFontSize(View.FONT_SIZE_TINY);
            this.add(mStatusLabel, BorderLayout.EAST);

            mChatStateLabel = new WebLabel();
            mChatStateLabel.setForeground(View.DARK_RED);
            mChatStateLabel.setFontSize(View.FONT_SIZE_NORMAL);
            mChatStateLabel.setBoldFont();
            //mChatStateLabel.setMargin(0, 5, 0, 5);

            this.add(
                    new GroupPanel(View.GAP_SMALL, false,
                            mTitleLabel,
                            new GroupPanel(GroupingType.fillFirst,
                                    Box.createGlue(), mStatusLabel, mChatStateLabel)
                    ), BorderLayout.CENTER);

            this.updateOnEDT(null);
        }

        @Override
        protected void render(int tableWidth, boolean isSelected) {
            this.setBackground(isSelected ? View.BLUE : mBackground);
        }

        @Override
        protected String getTooltipText() {
            return "<html><body>" +
                    Tr.tr("Last message:")+" " + lastActivity(mValue, false) + "<br>"
                    + "";
        }

        @Override
        protected void updateOnEDT(Object arg) {
            if (arg == null || arg == Chat.ViewChange.CONTACT ||
                    arg == Chat.ViewChange.SUBJECT) {
                mAvatar.setAvatarImage(mValue);
                mTitleLabel.setText(Utils.chatTitle(mValue));
            }

            if (arg == null || arg == Chat.ViewChange.NEW_MESSAGE ||
                    arg == Change.TIMER) {
                mStatusLabel.setText(lastActivity(mValue, true));
            }

            if (arg == null || arg == Chat.ViewChange.NEW_MESSAGE) {
                ChatListView.this.updateSorting();
            }

            if (arg == null || arg == Chat.ViewChange.READ) {
                mBackground = !mValue.isRead() ? View.LIGHT_BLUE : Color.WHITE;
            }

            String stateText = "";
            if (arg == Chat.ViewChange.MEMBER_STATE &&
                    mValue instanceof SingleChat) {
                Member member = ((SingleChat) mValue).getMember();
                switch(member.getState()) {
                    case composing: stateText = Tr.tr("is writing…"); break;
                    //case paused: activity = T/r.tr("stopped typing"); break;
                    //case inactive: stateText = T/r.tr("is inactive"); break;
                }
                // not used: chatstates for group chats
//                if (!stateText.isEmpty() && mValue.isGroupChat())
//                    stateText = member.getContact().getName() + ": " + stateText;
            }
            if (stateText.isEmpty()) {
                mChatStateLabel.setText("");
                mStatusLabel.setVisible(true);
            } else {
                mChatStateLabel.setText(stateText);
                mStatusLabel.setVisible(false);
            }
        }

        @Override
        protected boolean contains(String search) {
            // always show entry for current chat
            Chat chat = mView.getCurrentShownChat().orElse(null);
            if (chat != null && chat == mValue)
                return true;

            for (Contact contact: mValue.getAllContacts()) {
                if (contact.getName().toLowerCase().contains(search) ||
                        contact.getJID().string().toLowerCase().contains(search))
                    return true;
            }
            return mValue.getSubject().toLowerCase().contains(search);
        }

        @Override
        public int compareTo(TableItem o) {
            KonMessage m = this.mValue.getMessages().getLast().orElse(null);
            KonMessage oM = o.mValue.getMessages().getLast().orElse(null);
            if (m != null && oM != null)
                return -m.getDate().compareTo(oM.getDate());

            return -Integer.compare(this.mValue.getID(), o.mValue.getID());
        }
    }

    private static String lastActivity(Chat chat, boolean pretty) {
        KonMessage m = chat.getMessages().getLast().orElse(null);
        String lastActivity = m == null ? Tr.tr("no messages yet") :
                pretty ? Utils.PRETTY_TIME.format(m.getDate()) :
                Utils.MID_DATE_FORMAT.format(m.getDate());
        return lastActivity;
    }
}
