package net.primeblocks.relics.api;

import com.google.gson.annotations.SerializedName;

/** Body for {@code POST /api/auth/login}. */
public record StartLoginRequest(@SerializedName("name") String name) {
}
