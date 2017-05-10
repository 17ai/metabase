(ns metabase.sync-database.cached-values
  "Functions discover and save the fields in a table."
  (:require [clojure.tools.logging :as log]
            [metabase
             [driver :as driver]
             [util :as u]]
            [metabase.models
             [field :as field]
             [field-values :as field-values]
             [table :as table]]
            [metabase.sync-database.interface :as i]
            [schema.core :as schema]
            [toucan.db :as db]))

(defn analyze-table-data-shape!
  "Analyze the data shape for a single `Table`."
  [driver {table-id :id, :as table}]
  (let [new-field-ids (db/select-ids field/Field, :table_id table-id, :visibility_type [:not= "retired"], :last_analyzed nil)]
    ;; TODO: this call should include the database
    (when-let [table-stats (u/prog1 (driver/analyze-table driver table new-field-ids)
                             (when <>
                               (schema/validate i/AnalyzeTable <>)))]
      ;; update table row count
      (when (:row_count table-stats)
        (db/update! table/Table table-id, :rows (:row_count table-stats)))

      ;; update individual fields
      (doseq [{:keys [id preview-display special-type values]} (:fields table-stats)]
        ;; set Field metadata we may have detected
        (when (and id (or preview-display special-type))
          (db/update-non-nil-keys! field/Field id
            ;; if a field marked `preview-display` as false then set the visibility type to `:details-only` (see models.field/visibility-types)
            :visibility_type (when (false? preview-display) :details-only)
            :special_type    special-type))
        ;; handle field values, setting them if applicable otherwise clearing them
        (if (and id values (pos? (count (filter identity values))))
          (field-values/save-field-values! id values)
          (field-values/clear-field-values! id))))

    ;; update :last_analyzed for all fields in the table
    (db/update-where! field/Field {:table_id        table-id
                                   :visibility_type [:not= "retired"]}
      :last_analyzed (u/new-sql-timestamp))))

(defn analyze-data-shape-for-tables!
  "Perform in-depth analysis on the data shape for all `Tables` in a given DATABASE.
   This is dependent on what each database driver supports, but includes things like cardinality testing and table row counting.
   The bulk of the work is done by the `(analyze-table ...)` function on the IDriver protocol."
  [driver {database-id :id, :as database}]
  (log/info (u/format-color 'blue "Analyzing data in %s database '%s' (this may take a while) ..." (name driver) (:name database)))

  (let [start-time-ns         (System/nanoTime)
        tables                (db/select table/Table, :db_id database-id, :active true, :visibility_type nil)
        tables-count          (count tables)
        finished-tables-count (atom 0)]
    (doseq [{table-name :name, :as table} tables]
      (try
        (analyze-table-data-shape! driver table)
        (catch Throwable t
          (log/error "Unexpected error analyzing table" t))
        (finally
          (u/prog1 (swap! finished-tables-count inc)
            (log/info (u/format-color 'blue "%s Analyzed table '%s'." (u/emoji-progress-bar <> tables-count) table-name))))))

    (log/info (u/format-color 'blue "Analysis of %s database '%s' completed (%s)." (name driver) (:name database) (u/format-nanoseconds (- (System/nanoTime) start-time-ns))))))
