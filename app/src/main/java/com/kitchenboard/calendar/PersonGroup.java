package com.kitchenboard.calendar;

import java.util.List;

public class PersonGroup {
    private final long id;
    private final String name;
    private final List<Long> memberIds;  // IDs of persons in this group

    public PersonGroup(long id, String name, List<Long> memberIds) {
        this.id        = id;
        this.name      = name;
        this.memberIds = memberIds;
    }

    public long        getId()        { return id; }
    public String      getName()      { return name; }
    public List<Long>  getMemberIds() { return memberIds; }
}
