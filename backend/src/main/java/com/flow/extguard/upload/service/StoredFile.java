package com.flow.extguard.upload.service;

/**
 * A file successfully written to storage.
 *
 * @param storedName relative path under the storage root, e.g.
 *                   {@code 2026/09/18/3f2b...-....bin}. Contains no part of the
 *                   name the user supplied.
 * @param sizeBytes  bytes actually written
 * @param sha256     hex digest computed while streaming
 */
public record StoredFile(String storedName, long sizeBytes, String sha256) {
}
