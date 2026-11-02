package com.vibeminer.files;

import com.vibeminer.files.FileTypes.FileHole;
import java.io.IOException;

public final class FileHoleException extends IOException {
    private static final long serialVersionUID = 1L;
    private final transient FileHole hole;

    public FileHoleException(FileHole hole) {
        super("Range needs TACT key " + hole.keyName());
        this.hole = hole;
    }

    public FileHole hole() {
        return hole;
    }
}
