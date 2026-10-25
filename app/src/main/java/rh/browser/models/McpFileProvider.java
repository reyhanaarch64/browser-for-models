package rh.browser.models;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class McpFileProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    private File resolve(Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(name==null || name.length()==0 || name.indexOf("/")>=0 || name.indexOf("\\")>=0) throw new FileNotFoundException("invalid file name");
        File root=new File(getContext().getCacheDir(),"mcp_input_files");
        File file=new File(root,name);
        try { if(!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator)) throw new FileNotFoundException("invalid path"); }
        catch(IOException e){throw new FileNotFoundException("invalid path");}
        if(!file.isFile()) throw new FileNotFoundException("file not found");
        return file;
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException { return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY); }
    public String getType(Uri uri) { return "application/octet-stream"; }
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sortOrder) { return null; }
    public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
    public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
