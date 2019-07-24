/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.document.resolver;

import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import java9.util.function.LongConsumer;
import me.zhanghai.android.files.file.MimeTypes;
import me.zhanghai.android.files.provider.common.MoreFiles;
import me.zhanghai.android.files.provider.content.resolver.Cursors;
import me.zhanghai.android.files.provider.content.resolver.Resolver;
import me.zhanghai.android.files.provider.content.resolver.ResolverException;
import me.zhanghai.android.files.util.SetBuilder;

public class DocumentResolver {

    private static final String BUGREPORT_STORAGE_PROVIDER_AUTHORITY =
            "com.android.shell.documents";
    private static final String EXTERNAL_STORAGE_PROVIDER_AUTHORITY =
            "com.android.externalstorage.documents";
    private static final String MTP_DOCUMENTS_PROVIDER_AUTHORITY = "com.android.mtp.documents";

    private static final Set<String> COPY_UNSUPPORTED_AUTHORITIES =
            SetBuilder.<String>newHashSet()
                    .add(BUGREPORT_STORAGE_PROVIDER_AUTHORITY)
                    .add(EXTERNAL_STORAGE_PROVIDER_AUTHORITY)
                    .add(MTP_DOCUMENTS_PROVIDER_AUTHORITY)
                    .buildUnmodifiable();

    private static final Set<String> MOVE_UNSUPPORTED_AUTHORITIES =
            SetBuilder.<String>newHashSet()
                    .add(MTP_DOCUMENTS_PROVIDER_AUTHORITY)
                    .buildUnmodifiable();

    private static final Set<String> REMOVE_UNSUPPORTED_AUTHORITIES =
           SetBuilder.<String>newHashSet()
                    .add(BUGREPORT_STORAGE_PROVIDER_AUTHORITY)
                    .add(EXTERNAL_STORAGE_PROVIDER_AUTHORITY)
                    .add(MTP_DOCUMENTS_PROVIDER_AUTHORITY)
                    .buildUnmodifiable();

    private static final Map<Path, String> sPathDocumentIdCache = Collections.synchronizedMap(
            new WeakHashMap<>());

    private DocumentResolver() {}

    public static void checkExistence(@NonNull Path path) throws ResolverException {
        // Prevent cache from interfering with our check. Cache will be added again if
        // queryDocumentId() succeeds.
        sPathDocumentIdCache.remove(path);
        queryDocumentId(path);
    }

    @NonNull
    public static Uri copy(@NonNull Path sourcePath, @NonNull Path targetPath,
                           @Nullable LongConsumer listener, long intervalMillis)
            throws ResolverException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isCopyUnsupported(sourcePath)) {
            return copyApi24(sourcePath, targetPath, listener, intervalMillis);
        } else {
            return copyManually(sourcePath, targetPath, listener, intervalMillis);
        }
    }

    private static boolean isCopyUnsupported(@NonNull Path path) {
        String authority = path.getTreeUri().getAuthority();
        return COPY_UNSUPPORTED_AUTHORITIES.contains(authority);
    }

    @NonNull
    @RequiresApi(Build.VERSION_CODES.N)
    public static Uri copyApi24(@NonNull Path sourcePath, @NonNull Path targetPath,
                                @Nullable LongConsumer listener, long intervalMillis)
            throws ResolverException {
        Uri sourceUri = getDocumentUri(sourcePath);
        Uri targetParentUri = getDocumentUri(pathRequireParent(targetPath));
        Uri copiedTargetUri;
        try {
            // This doesn't support progress interval millis and interruption.
            copiedTargetUri = DocumentsContract.copyDocument(Resolver.getContentResolver(),
                    sourceUri, targetParentUri);
        } catch (UnsupportedOperationException e) {
            // Ignored.
            return copyManually(sourcePath, targetPath, listener, intervalMillis);
       } catch (Exception e) {
            throw new ResolverException(e);
        }
        if (copiedTargetUri == null) {
            throw new ResolverException("DocumentsContract.copyDocument() returned null: "
                    + sourceUri + ", " + targetParentUri);
        }
        String targetDisplayName = targetPath.getDisplayName();
        String sourceDisplayName = sourcePath.getDisplayName();
        if (Objects.equals(targetDisplayName, sourceDisplayName)) {
            maybeNotifyListenerWithSize(copiedTargetUri, listener);
            return copiedTargetUri;
        }
        Uri renamedTargetUri;
        try {
            try {
                renamedTargetUri = DocumentsContract.renameDocument(Resolver.getContentResolver(),
                        copiedTargetUri, sourceDisplayName);
            } catch (Exception e) {
                throw new ResolverException(e);
            }
            if (renamedTargetUri == null) {
                throw new ResolverException("DocumentsContract.renameDocument() returned null: "
                        + copiedTargetUri + ", " + sourceDisplayName);
            }
        } catch (ResolverException e) {
            try {
                remove(copiedTargetUri, targetParentUri);
            } catch (ResolverException e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
        maybeNotifyListenerWithSize(renamedTargetUri, listener);
        return renamedTargetUri;
    }

    @NonNull
    private static Uri copyManually(@NonNull Path sourcePath, @NonNull Path targetPath,
                                    @Nullable LongConsumer listener, long intervalMillis)
            throws ResolverException {
        Uri sourceUri = getDocumentUri(sourcePath);
        String mimeType;
        try {
            mimeType = getMimeType(sourceUri);
        } catch (ResolverException e) {
            e.printStackTrace();
            // TODO: Or null?
            mimeType = MimeTypes.GENERIC_MIME_TYPE;
        }
        if (Objects.equals(mimeType, MimeTypes.DIRECTORY_MIME_TYPE)) {
            return create(targetPath, MimeTypes.DIRECTORY_MIME_TYPE);
        } else {
            Uri targetUri = create(targetPath, mimeType);
            try (InputStream inputStream = Resolver.openInputStream(sourceUri, "r");
                 OutputStream outputStream = Resolver.openOutputStream(targetUri, "w")) {
                MoreFiles.copy(inputStream, outputStream, listener, intervalMillis);
            } catch (IOException e) {
                Path targetParentPath = targetPath.getParent();
                if (targetParentPath != null) {
                    try {
                        Uri targetParentUri = getDocumentUri(targetParentPath);
                        remove(targetUri, targetParentUri);
                    } catch (ResolverException e2) {
                        e.addSuppressed(e2);
                    }
                }
                throw new ResolverException(e);
            }
            return targetUri;
        }
    }

    public static Uri create(@NonNull Path path, String mimeType)
            throws ResolverException {
        Uri parentUri = getDocumentUri(pathRequireParent(path));
        Uri uri;
        try {
            uri = DocumentsContract.createDocument(Resolver.getContentResolver(), parentUri,
                    mimeType, path.getDisplayName());
        } catch (Exception e) {
            throw new ResolverException(e);
        }
        if (uri == null) {
            throw new ResolverException("DocumentsContract.createDocument() returned null: "
                    + parentUri);
        }
        // The display name might have been changed so we cannot add the new URI to cache.
        return uri;
    }

    /**
     * @deprecated Use {@link #remove(Path)} instead.
     */
    public static void delete(@NonNull Path path) throws ResolverException {
        Uri uri = getDocumentUri(path);
        // Always remove the path from cache, in case a deletion actually succeeded despite
        // exception being thrown.
        sPathDocumentIdCache.remove(path);
        //noinspection deprecation
        delete(uri);
    }

    /**
     * @deprecated Use {@link #remove(Path)} instead.
     */
    private static void delete(@NonNull Uri uri) throws ResolverException {
        boolean deleted;
        try {
            deleted = DocumentsContract.deleteDocument(Resolver.getContentResolver(), uri);
        } catch (Exception e) {
            throw new ResolverException(e);
        }
        if (!deleted) {
            throw new ResolverException("DocumentsContract.deleteDocument() returned false: "
                    + uri);
        }
    }

    public static boolean exists(@NonNull Path path) {
        try {
            checkExistence(path);
            return true;
        } catch (ResolverException e) {
            return false;
        }
    }

    @Nullable
    public static String getMimeType(@NonNull Path path) throws ResolverException {
        Uri uri = getDocumentUri(path);
        return getMimeType(uri);
    }

    @Nullable
    public static String getMimeType(@NonNull Uri uri) throws ResolverException {
        String mimeType;
        try (Cursor cursor = Resolver.query(uri,
                new String[] { DocumentsContract.Document.COLUMN_MIME_TYPE }, null, null, null)) {
            Cursors.moveToFirst(cursor);
            mimeType = Cursors.getString(cursor, DocumentsContract.Document.COLUMN_MIME_TYPE);
        }
        if (TextUtils.isEmpty(mimeType) || Objects.equals(mimeType, MimeTypes.GENERIC_MIME_TYPE)) {
            return null;
        }
        return mimeType;
    }

    public static long getSize(@NonNull Path path) throws ResolverException {
        Uri uri = getDocumentUri(path);
        return getSize(uri);
    }

    public static long getSize(@NonNull Uri uri) throws ResolverException {
        try (Cursor cursor = Resolver.query(uri,
                new String[] { DocumentsContract.Document.COLUMN_SIZE }, null, null, null)) {
            Cursors.moveToFirst(cursor);
            return Cursors.getLong(cursor, DocumentsContract.Document.COLUMN_SIZE);
        }
    }

    @NonNull
    public static Uri move(@NonNull Path sourcePath, @NonNull Path targetPath, boolean moveOnly,
                           @Nullable LongConsumer listener, long intervalMillis)
            throws ResolverException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isMoveUnsupported(sourcePath)) {
            return moveApi24(sourcePath, targetPath, moveOnly, listener, intervalMillis);
        } else {
            if (moveOnly) {
                throw new ResolverException(new UnsupportedOperationException(
                        // @see DocumentsProvider.moveDocument(String, String, String)
                        "Move not supported"));
            }
            return moveByCopy(sourcePath, targetPath, listener, intervalMillis);
        }
    }

    private static boolean isMoveUnsupported(@NonNull Path path) {
        String authority = path.getTreeUri().getAuthority();
        return MOVE_UNSUPPORTED_AUTHORITIES.contains(authority);
    }

    @NonNull
    @RequiresApi(Build.VERSION_CODES.N)
    public static Uri moveApi24(@NonNull Path sourcePath, @NonNull Path targetPath,
                                boolean moveOnly, @Nullable LongConsumer listener,
                                long intervalMillis)
            throws ResolverException {
        Uri sourceParentUri = getDocumentUri(pathRequireParent(sourcePath));
        Uri sourceUri = getDocumentUri(sourcePath);
        Uri targetParentUri = getDocumentUri(pathRequireParent(targetPath));
        Uri movedTargetUri;
        try {
            // This doesn't support progress interval millis and interruption.
            movedTargetUri = DocumentsContract.moveDocument(Resolver.getContentResolver(),
                    sourceUri, sourceParentUri, targetParentUri);
        } catch (UnsupportedOperationException e) {
            if (moveOnly) {
                throw new ResolverException(e);
            }
            return moveByCopy(sourcePath, targetPath, listener, intervalMillis);
        } catch (Exception e) {
            throw new ResolverException(e);
        }
        if (movedTargetUri == null) {
            throw new ResolverException("DocumentsContract.moveDocument() returned null: "
                    + sourceUri + ", " + targetParentUri);
        }
        String targetDisplayName = targetPath.getDisplayName();
        String sourceDisplayName = sourcePath.getDisplayName();
        if (Objects.equals(targetDisplayName, sourceDisplayName)) {
            maybeNotifyListenerWithSize(movedTargetUri, listener);
            return movedTargetUri;
        }
        Uri renamedTargetUri;
        try {
            renamedTargetUri = DocumentsContract.renameDocument(Resolver.getContentResolver(),
                    movedTargetUri, sourceDisplayName);
        } catch (Exception e) {
            throw new ResolverException(e);
        }
        if (renamedTargetUri == null) {
            throw new ResolverException("DocumentsContract.renameDocument() returned null: "
                    + movedTargetUri + ", " + sourceDisplayName);
        }
        maybeNotifyListenerWithSize(renamedTargetUri, listener);
        return renamedTargetUri;
    }

    private static void maybeNotifyListenerWithSize(@NonNull Uri uri,
                                                    @Nullable LongConsumer listener) {
        if (listener == null) {
            return;
        }
        long size;
        try {
            size = DocumentResolver.getSize(uri);
        } catch (ResolverException e) {
            e.printStackTrace();
            return;
        }
        listener.accept(size);
    }

    @NonNull
    private static Uri moveByCopy(@NonNull Path sourcePath, @NonNull Path targetPath,
                                  @Nullable LongConsumer listener, long intervalMillis)
            throws ResolverException {
        Uri targetUri = copy(sourcePath, targetPath, listener, intervalMillis);
        try {
            Uri sourceUri = getDocumentUri(sourcePath);
            Uri sourceParentUri = getDocumentUri(pathRequireParent(sourcePath));
            remove(sourceUri, sourceParentUri);
        } catch (ResolverException e) {
            try {
                Uri targetParentUri = getDocumentUri(pathRequireParent(targetPath));
                remove(targetUri, targetParentUri);
            } catch (ResolverException e2) {
                e.addSuppressed(e2);
            }
        }
        return targetUri;
    }

    @NonNull
    public static InputStream openInputStream(@NonNull Path path, @NonNull String mode)
            throws ResolverException {
        Uri uri = getDocumentUri(path);
        return Resolver.openInputStream(uri, mode);
    }

    @NonNull
    public static OutputStream openOutputStream(@NonNull Path path, @NonNull String mode)
            throws ResolverException {
        Uri uri = getDocumentUri(path);
        return Resolver.openOutputStream(uri, mode);
    }

    @NonNull
    public static ParcelFileDescriptor openParcelFileDescriptor(@NonNull Path path,
                                                                @NonNull String mode)
            throws ResolverException {
        Uri uri = getDocumentUri(path);
        return Resolver.openParcelFileDescriptor(uri, mode);
    }

    @NonNull
    public static List<Path> queryChildren(@NonNull Path parentPath)
            throws ResolverException {
        String parentDocumentId = queryDocumentId(parentPath);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentPath.getTreeUri(),
                parentDocumentId);
        List<Path> childrenPaths = new ArrayList<>();
        try (Cursor cursor = Resolver.query(childrenUri, new String[] {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        }, null, null, null)) {
            while (cursor.moveToNext()) {
                String childDocumentId = Cursors.requireString(cursor,
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                String childDisplayName = Cursors.requireString(cursor,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                Path childPath = parentPath.resolve(childDisplayName);
                sPathDocumentIdCache.put(childPath, childDocumentId);
                childrenPaths.add(childPath);
            }
        }
        return childrenPaths;
    }

    public static void remove(@NonNull Path path) throws ResolverException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isRemoveUnsupported(path)) {
            removeApi24(path);
        } else {
            //noinspection deprecation
            delete(path);
        }
    }

    public static void remove(@NonNull Uri uri, @NonNull Uri parentUri) throws ResolverException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isRemoveUnsupported(uri)) {
            removeApi24(uri, parentUri);
        } else {
            //noinspection deprecation
            delete(uri);
        }
    }

    private static boolean isRemoveUnsupported(@NonNull Path path) {
        String authority = path.getTreeUri().getAuthority();
        return REMOVE_UNSUPPORTED_AUTHORITIES.contains(authority);
    }

    private static boolean isRemoveUnsupported(@NonNull Uri uri) {
        String authority = uri.getAuthority();
        return REMOVE_UNSUPPORTED_AUTHORITIES.contains(authority);
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private static void removeApi24(@NonNull Path path) throws ResolverException {
        Uri uri = getDocumentUri(path);
        Path parentPath = path.getParent();
        if (parentPath == null) {
            throw new ResolverException("Path does not have a parent: " + path);
        }
        Uri parentUri = getDocumentUri(parentPath);
        removeApi24(uri, parentUri);
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private static void removeApi24(@NonNull Uri uri, @NonNull Uri parentUri)
            throws ResolverException {
        boolean removed;
        try {
            removed = DocumentsContract.removeDocument(Resolver.getContentResolver(), uri,
                    parentUri);
        } catch (UnsupportedOperationException e) {
            // Ignored.
            //noinspection deprecation
            delete(uri);
            return;
        } catch (Exception e) {
            throw new ResolverException(e);
        }
        if (!removed) {
            throw new ResolverException("DocumentsContract.removeDocument() returned false: "
                    + uri);
        }
    }

    @NonNull
    public static Uri getDocumentUri(@NonNull Path path) throws ResolverException {
        String documentId = queryDocumentId(path);
        return DocumentsContract.buildDocumentUriUsingTree(path.getTreeUri(), documentId);
    }

    @NonNull
    public static Uri getDocumentChildrenUri(@NonNull Path path) throws ResolverException {
        String documentId = queryDocumentId(path);
        return DocumentsContract.buildChildDocumentsUriUsingTree(path.getTreeUri(), documentId);
    }

    @NonNull
    private static String queryDocumentId(@NonNull Path path) throws ResolverException {
        String documentId = sPathDocumentIdCache.get(path);
        if (documentId != null) {
            return documentId;
        }
        Path parentPath = path.getParent();
        Uri treeUri = path.getTreeUri();
        if (parentPath == null) {
            documentId = DocumentsContract.getTreeDocumentId(treeUri);
        } else {
            String parentDocumentId = queryDocumentId(parentPath);
            documentId = queryChildDocumentId(parentDocumentId, path.getDisplayName(), treeUri);
        }
        sPathDocumentIdCache.put(path, documentId);
        return documentId;
    }

    @NonNull
    private static String queryChildDocumentId(@NonNull String parentDocumentId,
                                               @NonNull String displayName, @NonNull Uri treeUri)
            throws ResolverException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri,
                parentDocumentId);
        try (Cursor cursor = Resolver.query(childrenUri,
                new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID },
                DocumentsContract.Document.COLUMN_DISPLAY_NAME + " = ?",
                new String[] { displayName }, null)) {
            Cursors.moveToFirst(cursor);
            return Cursors.requireString(cursor, DocumentsContract.Document.COLUMN_DOCUMENT_ID);
        }
    }

    @NonNull
    private static Path pathRequireParent(@NonNull Path path) throws ResolverException {
        Path parentPath = path.getParent();
        if (parentPath == null) {
            throw new ResolverException("Path.getParent() returned null: " + path);
        }
        return parentPath;
    }

    public interface Path {

        @NonNull
        Uri getTreeUri();

        @Nullable
        String getDisplayName();

        @Nullable
        Path getParent();

        @NonNull
        Path resolve(@NonNull String other);
    }
}
