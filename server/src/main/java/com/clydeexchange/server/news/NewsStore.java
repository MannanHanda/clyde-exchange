package com.clydeexchange.server.news;

import java.util.List;

/** Where headlines are kept so the news panel is not empty after a restart. */
public interface NewsStore {

    /** Newest first. */
    List<NewsFeed.Item> latest(int limit);

    void save(List<NewsFeed.Item> items);
}
