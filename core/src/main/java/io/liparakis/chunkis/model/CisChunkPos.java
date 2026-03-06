package io.liparakis.chunkis.model;

/**
 * Platform-agnostic representation of a chunk position.
 *
 * @param x The chunk X coordinate
 * @param z The chunk Z coordinate
 */
public record CisChunkPos(int x, int z) {}
