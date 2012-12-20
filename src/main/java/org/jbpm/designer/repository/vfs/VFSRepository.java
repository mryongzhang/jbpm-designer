package org.jbpm.designer.repository.vfs;

import org.apache.commons.codec.binary.Base64;
import org.jbpm.designer.repository.*;
import org.jbpm.designer.repository.impl.AbstractAsset;
import org.jbpm.designer.repository.impl.AssetBuilder;
import org.jbpm.designer.web.profile.IDiagramProfile;
import org.kie.commons.io.IOService;
import org.kie.commons.io.impl.IOServiceDotFileImpl;
import org.kie.commons.io.impl.IOServiceNio2WrapperImpl;
import org.kie.commons.java.nio.IOException;
import org.kie.commons.java.nio.file.*;
import org.kie.commons.java.nio.file.attribute.BasicFileAttributes;

import java.io.File;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.*;

import static org.kie.commons.io.FileSystemType.Bootstrap.BOOTSTRAP_INSTANCE;

public class VFSRepository implements Repository {

    private final IOService ioService = new IOServiceNio2WrapperImpl();

    private URI repositoryRoot;
    private Path repositoryRootPath;

    private FileSystem fileSystem;

    public VFSRepository(IDiagramProfile profile) {
        // TODO build env from profile params?
        this(profile, new HashMap<String, String>());
    }

    public VFSRepository(IDiagramProfile profile, Map<String, String> env) {
        this.repositoryRoot = URI.create(profile.getRepositoryRoot());

        this.fileSystem = ioService.getFileSystem( this.repositoryRoot );

        if ( fileSystem == null ) {

            this.fileSystem = ioService.newFileSystem( this.repositoryRoot, env, BOOTSTRAP_INSTANCE );
        }

        // fetch file system changes - mainly for remote based file systems
        String fetchCommand = (String) env.get("fetch.cmd");
        if (fetchCommand != null) {
            this.fileSystem = ioService.getFileSystem(URI.create(profile.getRepositoryRoot() + fetchCommand));
        }
        this.repositoryRootPath = fileSystem.provider().getPath(this.repositoryRoot);
    }
    
    public Collection<Directory> listDirectories(String startAt) {
        Path path = fileSystem.provider().getPath(URI.create(getRepositoryRoot() + startAt));
        DirectoryStream<Path> directories = ioService.newDirectoryStream(path, new DirectoryStream.Filter<Path>() {

            public boolean accept( final Path entry ) throws IOException {
                if ( Files.isDirectory(entry) ) {
                    return true;
                }
                return false;
            }
        });
        Collection<Directory> foundDirectories = new ArrayList<Directory>();
        Iterator<Path> it = directories.iterator();
        while (it.hasNext()) {
            Path dir = it.next();
            foundDirectories.add(new Directory(dir.getFileName().toString(), trimLocation(dir)));
        }

        return foundDirectories;
    }

    public Collection<Asset> listAssetsRecursively(String startAt, final Filter filter) {
        final Collection<Asset> foundAssets = new ArrayList<Asset>();
        Path path = fileSystem.provider().getPath(URI.create(getRepositoryRoot() + startAt));

        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {

           public FileVisitResult visitFile(Path paths, BasicFileAttributes basicFileAttributes) throws IOException {
               if (filter.accept(paths)) {
                   foundAssets.add(buildAsset(paths, false));
               }
               return FileVisitResult.CONTINUE;
           }

        });

        return foundAssets;
    }

    public String createDirectory(String location) {
        Path path = fileSystem.provider().getPath(URI.create(getRepositoryRoot() + location));

        path = ioService.createDirectories(path);

        return path.toUri().toString();
    }

    public boolean directoryExists(String directory) {
        Path path = fileSystem.provider().getPath(URI.create(getRepositoryRoot() + directory));

        return ioService.exists(path);
    }

    public boolean deleteDirectory(String directory, boolean failIfNotEmpty) {

        try {
            Path path = fileSystem.provider().getPath(URI.create(getRepositoryRoot() + directory));

            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path paths, BasicFileAttributes basicFileAttributes) throws IOException {
                    ioService.delete(paths);

                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                    if (e == null) {
                        ioService.deleteIfExists(dir);
                        return FileVisitResult.CONTINUE;
                    } else {
                        // directory iteration failed
                        throw e;
                    }
                }

            });

            return true;
        } catch (Exception e)  {
            return false;
        }
    }

    public Collection<Asset> listAssets(String location) {
        Path path = fileSystem.provider().getPath(URI.create(getRepositoryRoot() + location));
        DirectoryStream<Path> directories = ioService.newDirectoryStream(path, new DirectoryStream.Filter<Path>() {

            public boolean accept( final Path entry ) throws IOException {
                if (!Files.isDirectory(entry)) {
                    return true;
                }
                return false;
            }
        });
        Collection<Asset> foundDirectories = new ArrayList<Asset>();
        Iterator<Path> it = directories.iterator();
        while (it.hasNext()) {
            Asset asset = buildAsset(it.next(), false);
            foundDirectories.add(asset);
        }

        return foundDirectories;
    }

    public Collection<Asset> listAssets(String location, final Filter filter) {
        Path path = fileSystem.provider().getPath(URI.create(getRepositoryRoot() + location));
        DirectoryStream<Path> directories = ioService.newDirectoryStream(path, new DirectoryStream.Filter<Path>() {

            public boolean accept( final Path entry ) throws IOException {

                return filter.accept(entry);
            }
        });
        Collection<Asset> foundDirectories = new ArrayList<Asset>();
        Iterator<Path> it = directories.iterator();
        while (it.hasNext()) {
            Asset asset = buildAsset(it.next(), false);
            foundDirectories.add(asset);
        }

        return foundDirectories;
    }

    public Asset loadAsset(String assetUniqueId) throws AssetNotFoundException {
        String uniqueId = decodeUniqueId(assetUniqueId);
        Path assetPath = fileSystem.provider().getPath(URI.create(uniqueId));

        Asset asset = buildAsset(assetPath, true);

        return asset;
    }

    public Asset loadAssetFromPath(String location) throws AssetNotFoundException {
        Path path = fileSystem.provider().getPath(URI.create(getRepositoryRoot() + location));

        if (ioService.exists(path)) {
            return loadAsset(path.toUri().toString());
        } else {
            throw new AssetNotFoundException();
        }

    }

    public String createAsset(Asset asset) {
        Path filePath = fileSystem.provider().getPath(URI.create(getRepositoryRoot() + (asset.getAssetLocation().equals("/")?"":asset.getAssetLocation()) + "/" +asset.getFullName()));
        if (!ioService.exists(filePath.getParent())) {
            try {
                fileSystem.provider().createDirectory(filePath.getParent(), null);
            } catch (FileAlreadyExistsException e) {
                // TODO currently git provider does not properly check existence of directories
            }
        }
        try {
            OutputStream outputStream = fileSystem.provider().newOutputStream(filePath, StandardOpenOption.TRUNCATE_EXISTING);
            if(((AbstractAsset)asset).acceptBytes()) {
                outputStream.write(((Asset<byte[]>)asset).getAssetContent());
            } else {
                outputStream.write(asset.getAssetContent().toString().getBytes());
            }
            outputStream.close();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Error when creating asset", e);
        }
        return encodeUniqueId(filePath.toUri().toString());
    }

    public String updateAsset(Asset asset) throws AssetNotFoundException {
        String uniqueId = decodeUniqueId(asset.getUniqueId());
        Path filePath = fileSystem.provider().getPath(URI.create(uniqueId));
        if (!ioService.exists(filePath)) {
            throw new AssetNotFoundException();
        }
        if(((AbstractAsset)asset).acceptBytes()) {
            ioService.write(filePath, ((Asset<byte[]>)asset).getAssetContent(), StandardOpenOption.TRUNCATE_EXISTING);
        } else {
            ioService.write(filePath, asset.getAssetContent().toString().getBytes(), StandardOpenOption.TRUNCATE_EXISTING);
        }

        return asset.getUniqueId();
    }

    public boolean deleteAsset(String assetUniqueId) {
        String uniqueId = decodeUniqueId(assetUniqueId);
        try {
            return ioService.deleteIfExists(fileSystem.provider().getPath(URI.create(uniqueId)));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean deleteAssetFromPath(String path) {

        Path filePath = fileSystem.provider().getPath(URI.create(getRepositoryRoot() + path));

        return deleteAsset(filePath.toUri().toString());
    }

    public boolean assetExists(String assetUniqueId) {
        String uniqueId = decodeUniqueId(assetUniqueId);
        try {
            return ioService.exists(fileSystem.provider().getPath(URI.create(uniqueId)));
        } catch (Exception e) {
            return ioService.exists(fileSystem.provider().getPath(URI.create(getRepositoryRoot() + assetUniqueId)));
        }
    }

    protected Asset buildAsset(Path file, boolean loadContent) {

        String name = file.getFileName().toString();
        String location = trimLocation(file);

        AssetBuilder assetBuilder = AssetBuilderFactory.getAssetBuilder(name);
        BasicFileAttributes attrs = fileSystem.provider().readAttributes(file, BasicFileAttributes.class);
        assetBuilder.uniqueId(encodeUniqueId(file.toUri().toString()))
                    .location(location)
                    .creationDate(attrs.creationTime() == null ? "" : attrs.creationTime().toString())
                    .lastModificationDate(attrs.lastModifiedTime() == null ? "" : attrs.lastModifiedTime().toString())
                    // TODO some provider specific details
                    .description("")
                    .owner("");

        if (loadContent) {
            if (((AbstractAsset)assetBuilder.getAsset()).acceptBytes()) {
                assetBuilder.content(ioService.readAllBytes(file));
            } else {
                assetBuilder.content(ioService.readAllString(file, Charset.forName("UTF-8")));
            }
        }

        return assetBuilder.getAsset();
    }

    private String decodeUniqueId(String uniqueId) {
        if (Base64.isBase64(uniqueId)) {
            byte[] decoded = Base64.decodeBase64(uniqueId);
            try {
                return new String(decoded, "UTF-8");
            } catch (UnsupportedEncodingException e) {

            }
        }

        return uniqueId;
    }

    private String encodeUniqueId(String uniqueId) {
        try {
            return Base64.encodeBase64URLSafeString(uniqueId.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    private String getRepositoryRoot() {
        String repo = this.repositoryRoot.toString();
        if (repo.endsWith("/")) {
            return repo.substring(0, repo.length()-2);
        }

        return repo;
    }

    private String trimLocation(Path file) {
        String location = "";
        String pathAsString =  file.getParent().toString();
        if(pathAsString.startsWith(this.repositoryRoot.getScheme())) {
            location = pathAsString.replaceFirst(getRepositoryRoot(), "");
        } else {
            location = pathAsString.replaceFirst(this.repositoryRootPath.toString(), "");
        }

        if (!location.startsWith(fileSystem.getSeparator())) {
            location = fileSystem.getSeparator() + location;
        }

        return location;
    }
}