package net.primeblocks.relics.model;

import java.util.List;

import com.google.gson.annotations.SerializedName;

/** Sets plus altar storage for one player on one database. Mirrors {@code RelicOverviewResponse}. */
public record RelicOverview(
		@SerializedName("database") String database,
		@SerializedName("owner") String owner,
		@SerializedName("sets") List<RelicSet> sets,
		@SerializedName("storage") List<StoredRelic> storage
) {
	public List<RelicSet> setsOrEmpty() {
		return sets != null ? sets : List.of();
	}

	public List<StoredRelic> storageOrEmpty() {
		return storage != null ? storage : List.of();
	}

	public boolean isEmpty() {
		return setsOrEmpty().isEmpty() && storageOrEmpty().isEmpty();
	}

	public RelicSet setById(int id) {
		for (RelicSet set : setsOrEmpty()) {
			if (set.id() == id) {
				return set;
			}
		}

		return null;
	}

	/** The set the API marks as equipped, or {@code null} if none is flagged. */
	public RelicSet activeSet() {
		for (RelicSet set : setsOrEmpty()) {
			if (set.active()) {
				return set;
			}
		}

		return null;
	}
}
