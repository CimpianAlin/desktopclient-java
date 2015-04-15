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

package org.kontalk.view;

import com.alee.extended.label.WebLinkLabel;
import com.alee.extended.panel.GroupPanel;
import com.alee.laf.label.WebLabel;
import com.alee.laf.list.UnselectableListModel;
import com.alee.laf.menu.WebMenuItem;
import com.alee.laf.menu.WebPopupMenu;
import com.alee.laf.panel.WebPanel;
import com.alee.laf.scroll.WebScrollPane;
import com.alee.laf.text.WebTextArea;
import com.alee.laf.viewport.WebViewport;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.logging.Level;
import javax.swing.AbstractCellEditor;
import javax.swing.Icon;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.ScrollPaneConstants;
import javax.swing.table.TableCellEditor;
import org.kontalk.crypto.Coder;
import org.kontalk.model.InMessage;
import org.kontalk.model.KonMessage;
import org.kontalk.model.KonThread;
import org.kontalk.model.MessageContent;
import org.kontalk.model.MessageContent.Attachment;
import org.kontalk.system.Downloader;
import org.kontalk.system.Config;
import org.kontalk.util.Tr;

/**
 * Pane that shows the currently selected thread.
 * @author Alexander Bikadorov <abiku@cs.tu-berlin.de>
 */
final class ThreadView extends WebScrollPane {
    private final static Logger LOGGER = Logger.getLogger(ThreadView.class.getName());

    private final static Icon PENDING_ICON = View.getIcon("ic_msg_pending.png");;
    private final static Icon SENT_ICON = View.getIcon("ic_msg_sent.png");
    private final static Icon DELIVERED_ICON = View.getIcon("ic_msg_delivered.png");
    private final static Icon ERROR_ICON = View.getIcon("ic_msg_error.png");
    private final static Icon CRYPT_ICON = View.getIcon("ic_msg_crypt.png");
    private final static Icon UNENCRYPT_ICON = View.getIcon("ic_msg_unencrypt.png");

    private final static SimpleDateFormat SHORT_DATE_FORMAT = new SimpleDateFormat("EEE, HH:mm");
    private final static SimpleDateFormat LONG_DATE_FORMAT = new SimpleDateFormat("EEE, d MMM yyyy, HH:mm:ss");

    private final View mView;

    private final Map<Integer, MessageViewList> mThreadCache = new HashMap<>();
    private Background mDefaultBG;

    ThreadView(View view) {
        super(null);
        mView = view;

        this.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        this.getVerticalScrollBar().setUnitIncrement(25);

        this.setViewport(new WebViewport() {
            @Override
            public void paintComponent(Graphics g) {
                super.paintComponent(g);
                Optional<BufferedImage> optBG =
                        ThreadView.this.getCurrentBackground().updateNowOrLater();
                // if there is something to draw, draw it now even if its old
                if (optBG.isPresent())
                    g.drawImage(optBG.get(), 0, 0, this.getWidth(), this.getHeight(), null);
            }
        });

        this.loadDefaultBG();
    }

    private Optional<MessageViewList> getCurrentView() {
        Component view = this.getViewport().getView();
        if (view == null)
            return Optional.empty();
        return Optional.of((MessageViewList) view);
    }

    Optional<KonThread> getCurrentThread() {
        Optional<MessageViewList> optview = this.getCurrentView();
        return optview.isPresent() ?
                Optional.of(optview.get().getThread()) :
                Optional.<KonThread>empty();
    }

    void showThread(KonThread thread) {
        boolean isNew = false;
        if (!mThreadCache.containsKey(thread.getID())) {
            MessageViewList newMessageList = new MessageViewList(thread);
            thread.addObserver(newMessageList);
            mThreadCache.put(thread.getID(), newMessageList);
            isNew = true;
        }
        MessageViewList table = mThreadCache.get(thread.getID());
        this.getViewport().setView(table);

        if (table.getRowCount() > 0 && isNew) {
            // trigger scrolling down
            table.mScrollDownOnResize = true;
        }

        thread.setRead();
    }

    void setColor(Color color) {
        this.getViewport().setBackground(color);
    }

    void loadDefaultBG() {
        String imagePath = Config.getInstance().getString(Config.VIEW_THREAD_BG);
        mDefaultBG = !imagePath.isEmpty() ?
                new Background(this.getViewport(), imagePath) :
                new Background(this.getViewport());
        this.getViewport().repaint();
    }

    private void removeThread(KonThread thread) {
        MessageViewList viewList = mThreadCache.get(thread.getID());
        if (viewList != null)
            viewList.clearItems();
        thread.deleteObserver(viewList);
        mThreadCache.remove(thread.getID());
        if(this.getCurrentThread().orElse(null) == thread) {
            this.setViewportView(null);
        }
    }

    private Background getCurrentBackground() {
        Optional<MessageViewList> optView = this.getCurrentView();
        if (!optView.isPresent())
            return mDefaultBG;
        Optional<Background> optBG = optView.get().getBG();
        if (!optBG.isPresent())
            return mDefaultBG;
        return optBG.get();
    }

    /**
     * View all messages of one thread in a left/right MIM style list.
     */
    private final class MessageViewList extends TableView<MessageViewList.MessageItem, KonMessage> {

        private final KonThread mThread;
        private boolean mScrollDownOnResize = false;
        private Optional<Background> mBackground = Optional.empty();

        MessageViewList(KonThread thread) {
            super();
            mThread = thread;

            // use custom editor (for mouse events)
            this.setDefaultEditor(TableView.TableItem.class, new TableEditor());

            //this.setEditable(false);
            //this.setAutoscrolls(true);
            this.setOpaque(false);

            this.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    // scrolling to a new component in the table
                    // is only possible after the component was rendered (which
                    // is now)
                    MessageViewList table = MessageViewList.this;
                    if (mScrollDownOnResize) {
                        table.scrollToRow(table.getRowCount() - 1);
                        mScrollDownOnResize = false;
                    }
                }
            });

            // disable selection
            this.setSelectionModel(new UnselectableListModel());

            // actions triggered by mouse events
            this.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    check(e);
                }
                @Override
                public void mouseReleased(MouseEvent e) {
                    check(e);
                }
                private void check(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        MessageViewList.this.showPopupMenu(e);
                    }
                }
            });

            this.setBackground(mThread.getViewSettings());

            this.updateOnEDT(null);
        }

        KonThread getThread() {
            return mThread;
        }

        Optional<Background> getBG() {
            return mBackground;
        }

        @Override
        protected void updateOnEDT(Object arg) {
            if (arg instanceof KonThread.ViewSettings) {
                this.setBackground((KonThread.ViewSettings) arg);
                if (ThreadView.this.getCurrentThread().orElse(null) == mThread) {
                    ThreadView.this.getViewport().repaint();
                }
                return;
            }

            if (mThread.isDeleted()) {
                ThreadView.this.removeThread(mThread);
            }

            // check for new messages to add
            if (this.getItems().size() < mThread.getMessages().size())
                this.insertMessages();

            if (ThreadView.this.getCurrentThread().orElse(null) == mThread) {
                mThread.setRead();
            }
        }

        private void insertMessages() {
            // TODO performance
            for (KonMessage message: mThread.getMessages()) {
                if (!this.containsValue(message)) {
                    this.addItem(new MessageItem(message));
                    this.setHeight(this.getRowCount() - 1);
                    // trigger scrolling
                    mScrollDownOnResize = true;
                }
            }
        }

        private void showPopupMenu(MouseEvent e) {
            int row = this.rowAtPoint(e.getPoint());
            if (row < 0)
                return;

            MessageItem messageView = this.getDisplayedItemAt(row);
            WebPopupMenu popupMenu = messageView.getPopupMenu();
            popupMenu.show(this, e.getX(), e.getY());
        }

        private void setBackground(KonThread.ViewSettings s) {
            JViewport p = ThreadView.this.getViewport();
            // simply overwrite
            if (s.getBGColor().isPresent()) {
                Color c = s.getBGColor().get();
                mBackground = Optional.of(new Background(p, c));
            } else if (!s.getImagePath().isEmpty()) {
                mBackground = Optional.of(new Background(p, s.getImagePath()));
            } else {
                mBackground = Optional.empty();
            }
        }

        /**
         * View for one message.
         * The content is added to a panel inside this panel.
         */
        final class MessageItem extends TableView<MessageItem, KonMessage>.TableItem {

            private final WebPanel mContentPanel;
            private final WebTextArea mTextArea;
            private final WebLabel mStatusIconLabel;
            private final int mPreferredTextAreaWidth;

            MessageItem(KonMessage message) {
                super(message);

                this.setOpaque(false);
                this.setMargin(2);
                //this.setBorder(new EmptyBorder(10, 10, 10, 10));

                WebPanel messagePanel = new WebPanel(true);
                messagePanel.setWebColoredBackground(false);
                messagePanel.setMargin(5);
                if (mValue.getDir().equals(KonMessage.Direction.IN))
                    messagePanel.setBackground(Color.WHITE);
                else
                    messagePanel.setBackground(View.LIGHT_BLUE);

                // from label
                if (mValue.getDir().equals(KonMessage.Direction.IN)) {
                    String from = getFromString(mValue);
                    WebLabel fromLabel = new WebLabel(" "+from);
                    fromLabel.setFontSize(12);
                    fromLabel.setForeground(Color.BLUE);
                    fromLabel.setItalicFont();
                    messagePanel.add(fromLabel, BorderLayout.NORTH);
                }

                mContentPanel = new WebPanel();
                mContentPanel.setOpaque(false);
                // text area
                mTextArea = new WebTextArea();
                mTextArea.setOpaque(false);
                mTextArea.setFontSize(13);
                mContentPanel.add(mTextArea, BorderLayout.CENTER);
                messagePanel.add(mContentPanel, BorderLayout.CENTER);

                WebPanel statusPanel = new WebPanel();
                statusPanel.setOpaque(false);
                statusPanel.setLayout(new FlowLayout(FlowLayout.TRAILING));
                // icons
                mStatusIconLabel = new WebLabel();

                this.updateView(null);

                // save the width that is requied to show the text in one line
                // before line wrap and only once!
                mPreferredTextAreaWidth = mTextArea.getPreferredSize().width;

                mTextArea.setLineWrap(true);
                mTextArea.setWrapStyleWord(true);

                statusPanel.add(mStatusIconLabel);
                WebLabel encryptIconLabel = new WebLabel();
                if (message.getCoderStatus().isSecure()) {
                    encryptIconLabel.setIcon(CRYPT_ICON);
                } else {
                    encryptIconLabel.setIcon(UNENCRYPT_ICON);
                }
                statusPanel.add(encryptIconLabel);
                // date label
                WebLabel dateLabel = new WebLabel(SHORT_DATE_FORMAT.format(mValue.getDate()));
                dateLabel.setForeground(Color.GRAY);
                dateLabel.setFontSize(11);
                statusPanel.add(dateLabel);
                messagePanel.add(statusPanel, BorderLayout.SOUTH);

                if (mValue.getDir().equals(KonMessage.Direction.IN)) {
                    this.add(messagePanel, BorderLayout.WEST);
                } else {
                    this.add(messagePanel, BorderLayout.EAST);
                }
            }

            @Override
            protected void resize(int listWidth) {
                // note: on the very first call the list width is zero
                int maxWidth = (int)(listWidth * 0.8);
                int width = Math.min(mPreferredTextAreaWidth, maxWidth);
                // height is reset later
                mTextArea.setSize(width, mTextArea.getPreferredSize().height);
            }

            @Override
            protected void updateOnEDT(Object arg) {
                this.updateView(arg);

                // find row of item...
                MessageViewList table = MessageViewList.this;
                int row;
                for (row = table.getRowCount()-1; row >= 0; row--)
                    if (this == table.getModel().getValueAt(row, 0))
                        break;
                // ...set height...
                table.setHeight(row);
                // ...and scroll down
                if (row == table.getRowCount()-1)
                    table.mScrollDownOnResize = true;
            }

            /**
             * Update what can change in a message: text, icon and attachment.
             */
            private void updateView(Object arg) {
                if (arg == null || arg instanceof String)
                    this.updateText();

                if (arg == null || arg instanceof KonMessage.Status)
                    this.updateStatus();

                if (arg == null || arg instanceof MessageContent.Attachment)
                    this.updateAttachment();
            }

            // text in text area, before/after encryption
            private void updateText() {
                boolean encrypted = mValue.getCoderStatus().isEncrypted();
                String text = encrypted ? Tr.tr("[encrypted]") : mValue.getContent().getText();
                mTextArea.setFontStyle(false, encrypted);
                mTextArea.setText(text);
                // hide area if there is no text
                mTextArea.setVisible(!text.isEmpty());
            }

            // status icon
            private void updateStatus() {
                if (mValue.getDir() == KonMessage.Direction.OUT) {
                    switch (mValue.getReceiptStatus()) {
                        case PENDING :
                            mStatusIconLabel.setIcon(PENDING_ICON);
                            break;
                        case SENT :
                            mStatusIconLabel.setIcon(SENT_ICON);
                            break;
                        case RECEIVED:
                            mStatusIconLabel.setIcon(DELIVERED_ICON);
                            break;
                        case ERROR:
                            mStatusIconLabel.setIcon(ERROR_ICON);
                            break;
                        default:
                            LOGGER.warning("unknown message receipt status!?");
                    }
                }
            }

            // attachment / image
            // TODO loading many images is very slow
            private void updateAttachment() {
                // remove possible old component (replacing does not work right)
                BorderLayout layout = (BorderLayout) mContentPanel.getLayout();
                Component oldComp = layout.getLayoutComponent(BorderLayout.SOUTH);
                if (oldComp != null)
                    mContentPanel.remove(oldComp);

                Optional<MessageContent.Attachment> optAttachment =
                        mValue.getContent().getAttachment();
                if (!optAttachment.isPresent())
                    return;

                Attachment att = optAttachment.get();
                String base = Downloader.getInstance().getAttachmentDir();
                String fName = att.getFileName();
                Path path = Paths.get(base, fName);

                // rely on mime type in message
                if (!att.getFileName().isEmpty() &&
                        att.getMimeType().startsWith("image")) {
                    WebLinkLabel imageView = new WebLinkLabel();
                    imageView.setLink("", linkRunnable(path));
                    // file should be present and should be an image, show it
                    ImageLoader.setImageIconAsync(imageView, path.toString());
                    mContentPanel.add(imageView, BorderLayout.SOUTH);
                    return;
                }

                // show a link to the file
                WebLabel attLabel;
                if (att.getFileName().isEmpty()) {
                    String statusText = Tr.tr("loading...");
                    switch (att.getDownloadProgress()) {
                        case 0:
                        case -2: statusText = Tr.tr("downloading..."); break;
                        case -3: statusText = Tr.tr("download failed"); break;
                    }
                    attLabel = new WebLabel(statusText);
                } else {
                    WebLinkLabel linkLabel = new WebLinkLabel();
                    linkLabel.setLink(fName, linkRunnable(path));
                    attLabel = linkLabel;
                }
                WebLabel labelLabel = new WebLabel(Tr.tr("Attachment:")+" ");
                labelLabel.setItalicFont();
                GroupPanel attachmentPanel = new GroupPanel(4, true, labelLabel, attLabel);
                mContentPanel.add(attachmentPanel, BorderLayout.SOUTH);
            }

            private WebPopupMenu getPopupMenu() {
                WebPopupMenu popupMenu = new WebPopupMenu();
                if (mValue.getCoderStatus().isEncrypted()) {
                    WebMenuItem decryptMenuItem = new WebMenuItem(Tr.tr("Decrypt"));
                    decryptMenuItem.setToolTipText(Tr.tr("Retry decrypting message"));
                    decryptMenuItem.addActionListener(new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent event) {
                            KonMessage m = MessageItem.this.mValue;
                            if (!(m instanceof InMessage)) {
                                LOGGER.warning("decrypted message not incoming message");
                                return;
                            }
                            ThreadView.this.mView.callDecrypt((InMessage) m);
                        }
                    });
                    popupMenu.add(decryptMenuItem);
                }
                WebMenuItem cItem = View.createCopyMenuItem(
                        this.toPrettyString(),
                        Tr.tr("Copy message content"));
                popupMenu.add(cItem);
                return popupMenu;
            }

            private String toPrettyString() {
                String date = LONG_DATE_FORMAT.format(mValue.getDate());
                String from = getFromString(mValue);
                return date + " - " + from + " : " + mValue.getContent().getText();
            }

            @Override
            protected String getTooltipText() {
                String encryption = Tr.tr("unknown");
                switch (mValue.getCoderStatus().getEncryption()) {
                    case NOT: encryption = Tr.tr("not encrypted"); break;
                    case ENCRYPTED: encryption = Tr.tr("encrypted"); break;
                    case DECRYPTED: encryption = Tr.tr("decrypted"); break;
                }
                String verification = Tr.tr("unknown");
                switch (mValue.getCoderStatus().getSigning()) {
                    case NOT: verification = Tr.tr("not signed"); break;
                    case SIGNED: verification = Tr.tr("signed"); break;
                    case VERIFIED: verification = Tr.tr("verified"); break;
                }
                String problems = "";
                if (mValue.getCoderStatus().getErrors().isEmpty()) {
                    problems = Tr.tr("none");
                } else {
                  for (Coder.Error error: mValue.getCoderStatus().getErrors()) {
                      problems += error.toString() + " <br> ";
                  }
                }

                String html = "<html><body>" +
                        //"<h3>Header</h3>" +
                        "<br>" +
                        Tr.tr("Security")+": " + encryption + " / " + verification + "<br>" +
                        Tr.tr("Problems")+": " + problems;

                return html;
            }

            @Override
            protected boolean contains(String search) {
                return mTextArea.getText().toLowerCase().contains(search) ||
                        mValue.getUser().getName().toLowerCase().contains(search) ||
                        mValue.getJID().toLowerCase().contains(search);
            }
        }
    }

    /** A background image of thread view with efficient async reloading. */
    private final class Background implements ImageObserver {
        private final Component mParent;
        // background image from resource or user selected
        private Image mOrigin;
        // cached background with size of viewport
        private BufferedImage mCached = null;
        private Color mBottomColor = null;

        /** Default, no thread specific settings. */
        Background(Component parent) {
            mParent = parent;
            mOrigin = View.getImage("thread_bg.png");
            mBottomColor = new Color(255, 255, 255, 255);
        }

        /** Image set by user (global or only for thread). */
        Background(Component parent, String imagePath) {
            mParent = parent;
            // loading async!
            mOrigin = Toolkit.getDefaultToolkit().createImage(imagePath);
        }

        /** Thread specific color. */
        Background(Component parent, Color bottomColor) {
            this(parent);
            mBottomColor = bottomColor;
        }

        /**
         * Update the background image for this parent. Returns immediately, but
         * repaints parent if updating is done asynchronously.
         * @return if synchronized update is possible the updated image, else an
         * old image if present
         */
        Optional<BufferedImage> updateNowOrLater() {
            if (mCached == null ||
                    mCached.getWidth() != mParent.getWidth() ||
                    mCached.getHeight() != mParent.getHeight()) {
                if (this.loadOrigin()) {
                    // goto 2
                    this.scaleOrigin();
                }
            }
            return Optional.ofNullable(mCached);
        }

        // step 1: ensure original image is loaded
        private boolean loadOrigin() {
            return mOrigin.getWidth(this) != -1;
        }

        // step 2: scale image
        private boolean scaleOrigin() {
            Image scaledImage = ImageLoader.scale(mOrigin, mParent.getWidth(), mParent.getHeight(), true);
            if (scaledImage.getWidth(this) != -1) {
                // goto 3
                this.updateCachedBG(scaledImage);
                return true;
            }
            return false;
        }

        // step 3: paint cache from scaled image
        private void updateCachedBG(Image scaledImage) {
            int width = mParent.getWidth();
            int height = mParent.getHeight();
            mCached = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D cachedG = mCached.createGraphics();
            // gradient background of background
            if (mBottomColor != null) {
                GradientPaint p2 = new GradientPaint(
                        0, 0, new Color(0, 0, 0, 0),
                        0, height, mBottomColor);
                cachedG.setPaint(p2);
                cachedG.fillRect(0, 0, width, getHeight());
            }
            // tiling
            int iw = scaledImage.getWidth(null);
            int ih = scaledImage.getHeight(null);
            for (int x = 0; x < width; x += iw) {
                for (int y = 0; y < height; y += ih) {
                    cachedG.drawImage(scaledImage, x, y, iw, ih, null);
                }
            }
        }

        @Override
        public boolean imageUpdate(Image img, int infoflags, int x, int y, int w, int h) {
            // ignore if image is not completely loaded
            if ((infoflags & ImageObserver.ALLBITS) == 0) {
                return true;
            }

            if (img.equals(mOrigin)) {
                // original image done loading, goto 2
                boolean sync = this.scaleOrigin();
                if (sync)
                    mParent.repaint();
                return false;
            } else {
                // scaling done, goto 3
                this.updateCachedBG(img);
                mParent.repaint();
                return false;
            }
        }
    }

    // needed for correct mouse behaviour for components in items
    // (and breaks selection behaviour somehow)
    private class TableEditor extends AbstractCellEditor implements TableCellEditor {
        private TableView<?, ?>.TableItem mValue;
        @Override
        public Component getTableCellEditorComponent(JTable table,
                Object value,
                boolean isSelected,
                int row,
                int column) {
            mValue = (TableView.TableItem) value;
            return mValue;
        }
        @Override
        public Object getCellEditorValue() {
            return mValue;
        }
    }

    private static String getFromString(KonMessage message) {
        String from;
        if (!message.getUser().getName().isEmpty()) {
            from = message.getUser().getName();
        } else {
            from = message.getJID();
            if (from.length() > 40)
                from = from.substring(0, 8) + "...";
        }
        return from;
    }

    private static Runnable linkRunnable(final Path path) {
        return new Runnable () {
                @Override
                public void run () {
                    Desktop dt = Desktop.getDesktop();
                    try {
                        dt.open(new File(path.toString()));
                    } catch (IOException ex) {
                        LOGGER.log(Level.WARNING, "can't open attachment", ex);
                    }
                }
            };
    }
}
