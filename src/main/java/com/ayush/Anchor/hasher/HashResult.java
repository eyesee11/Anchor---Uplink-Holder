package com.ayush.Anchor.hasher;

import com.fasterxml.jackson.annotation.JsonProperty; //Jackson's JSON property (maps JSON field names to Java Field Names)
                                                      // (snake_case to camelCase)


public class HashResult {  //just a DTO(Data Transfer Object) (holds Deserailized JSON.)
    @JsonProperty("file")       public String file;
    @JsonProperty("sha256")     public String sha256;
    @JsonProperty("size_bytes") public long   sizeBytes;
    @JsonProperty("error")      public String error; //if it's not null, the C binary failed
}



