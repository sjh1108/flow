package com.flow.extguard.upload.service;

import java.io.IOException;
import java.io.InputStream;

public interface FileStorage {

    StoredFile store(InputStream content) throws IOException;

    /** Compensating delete, used when the database write after a store fails. */
    void delete(String storedName);
}
