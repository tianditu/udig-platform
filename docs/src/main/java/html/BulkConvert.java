package html;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JFileChooser;

public class BulkConvert {
    /**
     * Used to shortlist index.html file
     */
    private static final class IndexFileFilter extends javax.swing.filechooser.FileFilter {
        public String getDescription() {
            return "index.html";
        }

        @Override
        public boolean accept(File f) {
            return f.getName().equals("index.html");
        }
    }

    /**
     * Used to list html files for conversion.
     */
    private final class HtmlFileFilter implements FileFilter {
        public boolean accept(File file) {
            return file.getName().endsWith(".html");
        }
    }

    public static void main(String args[]) {
        if (args.length == 1 && "?".equals(args[0])) {
            System.out.println(" Usage: java html.BulkConvert [index.html] [rst directory]");
            System.out.println();
            System.out.println("Where:");
            System.out.println("  index.html Where you have unzipped the confluence html export");
            System.out
                    .println("  rst directory location where you would like the html files saved");
            System.out.println();
            System.out
                    .println("If not provided the appication will prompt you for the above information");

            System.exit(0);
        }
        File indexFile = args.length > 0 ? new File(args[0]) : null;
        File rstDir = args.length > 1 ? new File(args[1]) : null;

        if (indexFile != null && indexFile.isDirectory()) {
            indexFile = new File(indexFile, "index.html");
        }

        if (indexFile == null || !indexFile.exists()) {
            File cd = new File(".");

            JFileChooser dialog = new JFileChooser(cd);
            dialog.setFileFilter(new IndexFileFilter());
            dialog.setDialogTitle("Locate Confluence wiki html export");
            int open = dialog.showDialog(null, "Convert");

            if (open == JFileChooser.CANCEL_OPTION) {
                System.out.println("Conversion canceled");
                System.exit(-1);
            }
            indexFile = dialog.getSelectedFile();
        }
        if (indexFile == null || !indexFile.exists() || indexFile.isDirectory()) {
            System.out.println("File index.html to use for conversion not provided: '" + indexFile
                    + "'");
            System.exit(-1);
        }
        File htmlDirectory = null;
        try {
            htmlDirectory = indexFile.getParentFile().getCanonicalFile();
        } catch (IOException eek) {
            System.out.println("Coudl not sort parent of " + indexFile + ":" + eek);
            System.exit(-1);
        }

        if (rstDir == null) {
            JFileChooser dialog = new JFileChooser(htmlDirectory.getParentFile());

            dialog.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            dialog.setDialogTitle("Target Directory for textile files");

            int open = dialog.showDialog(null, "Export");

            if (open == JFileChooser.CANCEL_OPTION) {
                System.out.println("Canceled canceled");
                System.exit(-1);
            }
            rstDir = dialog.getSelectedFile();
        }
        if (rstDir == null || !rstDir.isDirectory() || !rstDir.exists()) {
            System.out.println("Taget directory for textile files not provided: '" + rstDir + "'");
            System.exit(-1);
        }

        BulkConvert bulk = new BulkConvert(htmlDirectory, rstDir);
        bulk.convert();
    }

    private void convert() {

        File[] htmlFileList = htmlDirectory.listFiles(new HtmlFileFilter());
        for (File htmlFile : htmlFileList) {
            // File dir = htmlFile.getParentFile();

            String htmlName = htmlFile.getName();
            if( htmlName.equals("index.html")){
                continue; // skip this one
            }
            String rstName = htmlName.substring(0, htmlName.lastIndexOf('.')) + ".rst";

            File rstFile = new File(rstDirectory, rstName);
            System.out.println(htmlFile + " to " + rstFile.getName());
            if (rstFile.exists()) {
                boolean deleted = rstFile.delete();
                if (!deleted) {
                    System.out.println("\tCould not remove '" + rstFile
                            + "' do you have it open in an editor?");
                    System.out.println("\tSkipping ...");
                    continue;
                }
            }
            boolean success = pandoc(htmlFile, rstFile);
            if (!success) {
                break;
            }
            fixRstAndMoveImages(rstFile);
        }
    }

    private File htmlDirectory;

    private File rstDirectory;

    private BulkConvert(File htmlDirectory, File rstDir) {
        this.htmlDirectory = htmlDirectory;
        this.rstDirectory = rstDir;
    }

    String baseName(File file) {
        String name = file.getName();
        int split = name.lastIndexOf('.');
        if (split == -1) {
            return name;
        }
        String page = name.substring(0, split);
        page = page.replace(' ', '_');
        page = page.toLowerCase();

        return page;
    }

    String baseTitle(File file) {
        String name = file.getName();
        int split = name.lastIndexOf('.');
        if (split == -1) {
            return name;
        }
        String page = name.substring(0, split);

        return page;
    }

    /**
     * Process the generated rstFile and perform a few fixes.
     * 
     * @param rstFile
     */
    private boolean fixRstAndMoveImages(File rstFile) {
        File imageDir = new File(rstFile.getParent(), "images");
        String page = baseName(rstFile);
        String title = baseTitle(rstFile);

        // Fix image location
        // BEFORE: .. |image0| image:: download/attachments/3536/view.gif
        //  AFTER: .. |image0| image:: /image/active_part/view.gif
        //
        // Use http://www.regexplanet.com/advanced/java/index.html to work out
        //
        // pattern: .. \|image(\d)\| image:: (download/attachments/.+?)/(.+)
        // replace: .. |image$1| image:: /page/$3
        Pattern imagePattern = Pattern
                .compile(".. \\|image(\\d+)\\| image:: (download/attachments/.+?)/(.+)");
        String imageReplace = ".. |image$1| image:: /images/" + page + "/$3";

        // BEFORE: .. |image10| image:: download/attachments/4523/delete_feature_mode.gif
        //  AFTER: .. |image10| image:: /image/edit_tools/delete_feature_mode.gif
        
        Pattern figurePattern = Pattern.compile(".. figure:: (download/attachments/.+?)/(.+)");
        String figureReplace = ".. figure:: /images/" + page + "/$2";

        // Fix Related cross references
        // .. figure:: http://udig.refractions.net/image/EN/ngrelc.gif
        Map<Pattern, String> related = new HashMap<Pattern, String>();
        related.put(Pattern.compile(".. figure:: http://udig.refractions.net/image/EN/ngrelt.gif"),
                "**Related tasks**");
        related.put(Pattern.compile(".. figure:: http://udig.refractions.net/image/EN/ngrelr.gif"),
                "**Related reference**");
        related.put(Pattern.compile(".. figure:: http://udig.refractions.net/image/EN/ngrelc.gif"),
                "**Related concepts**");

        Pattern linkPattern = Pattern.compile("(\\s*)-\\s*`(.*) <(.*)>`_");
        String linkReplace = "$1* :doc:`$2`";
        Pattern strightLinkPattern = Pattern.compile("(\\s*)`(.*) <(.*)>`_");
        String straightLinkReplace = "$1:doc:`$2`";

        Pattern heading = Pattern.compile("(=|-|~|\\^|\\')+");
        final String LEVEL = "=-~^'";

        StringBuilder contents = new StringBuilder();
        BufferedReader reader = null;
        String line;

        int pageLevel = -1; // no heading encountered yet
        boolean includesTitle = false;
        boolean lineFeedNeeded = false;

        String previousLine = null;
        try {
            reader = new BufferedReader(new FileReader(rstFile));

            while ((line = reader.readLine()) != null) {
                if( line.contains("delete_feature_mode.gif")){
                    // breakpoint for debugging
                    System.out.println("Check delete_feature_mode.gif ");
                }
                // check headings
                if (heading.matcher(line).matches()) {
                    int level = LEVEL.indexOf(line.charAt(1));
                    if (pageLevel == -1) {
                        pageLevel = level; // you are the first heading
                        if (previousLine.equals(title)) {
                            // title already emitted!
                            includesTitle = true;
                        }
                    }
                }
                // Fix Related References
                for (Entry<Pattern, String> entry : related.entrySet()) {
                    Matcher matcher = entry.getKey().matcher(line);
                    if (matcher.matches()) {
                        line = matcher.replaceAll(entry.getValue());
                        reader.readLine(); // skip :align: center
                        reader.readLine(); // skip :alt:

                        lineFeedNeeded = true;
                    }
                }

                // Fix document links
                Matcher linkMatcher = linkPattern.matcher(line);
                if (linkMatcher.matches()) {
                    String indent = linkMatcher.group(1);
                    String pageRef = linkMatcher.group(2);
                    String htmlRef = linkMatcher.group(3);
                    // BEFORE: - `some page <some%20page.html>`_
                    // AFTER: * :doc:`some page`
                    line = linkMatcher.replaceAll(linkReplace);
                    lineFeedNeeded = true;
                }
                Matcher straightLinkMatcher = strightLinkPattern.matcher(line);
                if (straightLinkMatcher.matches()) {
                    String indent = straightLinkMatcher.group(1);
                    String pageRef = straightLinkMatcher.group(2);
                    String htmlRef = straightLinkMatcher.group(3);
                    // BEFORE: `some page <some%20page.html>`_
                    // AFTER: :doc:`some page`
                    line = straightLinkMatcher.replaceAll(straightLinkReplace);
                    lineFeedNeeded = true;
                }
                // check for image references
                //
                Matcher imageMatcher = imagePattern.matcher(line);
                if (imageMatcher.matches()) {
                    String image = imageMatcher.group(1);
                    String path = imageMatcher.group(2);
                    String file = imageMatcher.group(3);

                    File attachementImage = new File(new File(htmlDirectory, path), file);
                    File pageDir = new File(imageDir, page);
                    File pageImage = new File(pageDir, file);

                    duplicateImage(attachementImage, pageImage);
                    boolean moved = pageImage.exists();
                    if (moved) {
                        line = imageMatcher.replaceAll(imageReplace);
                    }
                }
                // check for figure references
                //
                Matcher figureMatcher = figurePattern.matcher(line);
                if (figureMatcher.matches()) {
                    String path = figureMatcher.group(1);
                    String file = figureMatcher.group(2);

                    File attachementImage =new File( new File(htmlDirectory,path), file);
                    File pageDir = new File(imageDir, page);
                    File pageImage = new File(pageDir, file);

                    duplicateImage(attachementImage, pageImage);
                    boolean moved = pageImage.exists();
                    if (moved) {
                        line = figureMatcher.replaceAll(figureReplace);
                    }
                }
                contents.append(line);
                contents.append("\n");
                if (lineFeedNeeded) {
                    contents.append("\n");
                    lineFeedNeeded = false;
                }
                previousLine = line; // remember for next time in case this is a heading!
            }
        } catch (FileNotFoundException e) {
            System.out.println("Unable to read '" + rstFile + "':" + e);
            return false;
        } catch (IOException e) {
            System.out.println("Trouble reading '" + rstFile + "':" + e);
            return false;
        } finally {
            close(reader);
        }
        // prepend title if needed
        if (!includesTitle && title != null && !title.isEmpty()) {
            char chars[] = new char[title.length()];
            Arrays.fill(chars, '#');
            String underline = new String(chars);
            contents.insert(0, "\n");
            contents.insert(0, "\n");
            contents.insert(0, underline);
            contents.insert(0, "\n");
            contents.insert(0, title);
        }

        String text = contents.toString();
        boolean deleted = rstFile.delete();
        if (deleted) {
            // write out modified copy
            try {
                OutputStream modifiedCopy = new BufferedOutputStream(new FileOutputStream(rstFile));

                InputStream textSteram = new ByteArrayInputStream(text.getBytes(Charset
                        .defaultCharset()));
                bufferedStreamsCopy(textSteram, modifiedCopy);
            } catch (IOException eek) {
                System.out.println("Trouble writing modified '" + rstFile + "':" + eek);
                return false;
            }
        }
        return true;
    }

    private void duplicateImage(File attachementImage, File pageImage) {
        String fileName = attachementImage.getName();
        
        File liveImage = searchPluginImages(fileName);
        if( liveImage != null ){
            System.out.println("   Override attachment image from : "+ liveImage );
            attachementImage = liveImage;
        }
        else if (!attachementImage.exists()) {
            File otherAttachment = searchAllAttachments(fileName);
            if( otherAttachment != null ){
                System.out.println("   Found attachment on another page " + otherAttachment );
                attachementImage = otherAttachment; // okay we found it on another page
            }
            else {
                System.out.println("   WARNING: Unable to locate " + attachementImage + " broken link!");                
            }
        }

        File pageDir = pageImage.getParentFile();
        if (!pageDir.exists()) {
            pageDir.mkdirs();
        }
        if (!pageImage.exists()) {
            System.out.println("   Copy image to  " + pageImage);
            bufferedStreamsCopy(attachementImage, pageImage);
        } else {
            boolean deleted = pageImage.delete();
            if (deleted) {
                System.out.println("   Copy image to  " + pageImage);
                bufferedStreamsCopy(attachementImage, pageImage);
            } else {
                System.out.println("   WARNING: Unable to repalce " + pageImage);
            }
        }
    }

    /** Search for the provided filename */
    private File searchAllAttachments(String fileName) {
        // search downloads
        File attachments = new File( new File(htmlDirectory, "download"),"attachments");
        if (attachments.exists() && attachments.isDirectory()) {
            File file = searchDirectory(attachments, fileName);
            if (file != null) {
                return file; // found it!
            }
        }
        return null; // not found!
    }

    private File searchPluginImages(String fileName) {
        // search for live icons first!
        File checkout = rstDirectory.getParentFile().getParentFile().getParentFile();
        File plugins = new File(checkout, "plugins");
        if( plugins.exists() && plugins.isDirectory() ){
            for (File plugin : plugins.listFiles()) {
                File icons = new File(plugin, "icons");
                if (icons.exists() && icons.isDirectory()) {
                    File file = searchDirectory(icons, fileName);
                    if (file != null) {
                        return file; // found it!
                    }
                }
            }
        }
        return null; // not found
    }

    private File searchDirectory(File dir, String fileName) {
        for( File file : dir.listFiles() ){
            if( file.isDirectory() ){
                File found = searchDirectory(file, fileName);
                if( found != null ){
                    return found; // found!
                }
            }
            if( fileName.equals( file.getName() ) ){
                return file;
            }
        }
        return null; // not found!
    }

    private boolean pandoc(File htmlFile, File rstFile) {

        String rstFilePath = rstFile.getAbsolutePath().toString();
        String htmlFilePath = htmlFile.getAbsolutePath().toString();

        System.out.println("/usr/local/bin/pandoc --columns=100 -o \"" + rstFilePath + "\" \""
                + htmlFilePath + "\"");
        String run[] = new String[] { "/usr/local/bin/pandoc", "--columns=100", "-o", rstFilePath,
                htmlFilePath };
        try {
            Process process = Runtime.getRuntime().exec(run, null, htmlFile.getParentFile());
            int exit = process.waitFor();
            System.out.println("\tGenerated " + rstFile.getName() + " with exist code " + exit);
            return exit == 0;
        } catch (InterruptedException e) {
            System.out.println("\\tFailed on " + rstFile.getName() + " with: " + e);
            return false;
        } catch (IOException e) {
            System.out.println("\\tFailed on " + rstFile.getName() + " with: " + e);
            e.printStackTrace();
            return false;
        }
    }

    private boolean html2res(File htmlFile, File rstFile) {
        String run[] = new String[] { "/usr/local/bin/html2rest", htmlFile.getName() };

        BufferedInputStream inputStream = null;
        BufferedOutputStream outputStream = null;
        try {
            Process process = Runtime.getRuntime().exec(run, null, htmlFile.getParentFile());
            // inputStream = new BufferedInputStream(new FileInputStream(htmlFile));
            // outputStream = new BufferedOutputStream( new FileOutputStream(rstFile));

            inputStream = new BufferedInputStream(process.getInputStream());
            outputStream = new BufferedOutputStream(new FileOutputStream(rstFile));

            bufferedStreamsCopy(inputStream, outputStream);

            process.waitFor();
            int exit = process.exitValue();
            System.out.println("\tGenerated " + rstFile.getName() + " with exist code " + exit);
            return exit == 0;
        } catch (IOException e) {
            System.out.println("\\tFailed on " + rstFile.getName() + " with: " + e);
            e.printStackTrace();
            return false;
        } catch (InterruptedException e) {
            System.out.println("\\tFailed on " + rstFile.getName() + " with: " + e);
            return false;
        }
    }

    private void bufferedStreamsCopy(File origional, File copy) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new BufferedInputStream(new FileInputStream(origional));
            out = new BufferedOutputStream(new FileOutputStream(copy));

            bufferedStreamsCopy(in, out);
        } catch (Exception eek) {
            System.out.print("Unable to copy from " + origional.getName() + "to " + copy);
            eek.printStackTrace();
        }
    }

    // From http://java.dzone.com/articles/file-copy-java-�-benchmark
    private void bufferedStreamsCopy(InputStream fin, OutputStream fout) throws IOException {
        try {
            int data;
            while ((data = fin.read()) != -1) {
                fout.write(data);
            }
            fout.flush();
        } finally {
            close(fin);
            close(fout);
        }
    }

    private void close(Closeable closable) {
        if (closable != null) {
            try {
                closable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
