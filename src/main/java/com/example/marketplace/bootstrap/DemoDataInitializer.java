package com.example.marketplace.bootstrap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.example.marketplace.listing.ItemCondition;
import com.example.marketplace.listing.Listing;
import com.example.marketplace.listing.ListingRepository;
import com.example.marketplace.user.UserAccount;
import com.example.marketplace.user.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "campusloop.seed.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataInitializer implements CommandLineRunner {

    private static final List<DemoListing> DEMO_LISTINGS = List.of(
            new DemoListing("Calculus: Early Transcendentals",
                    "Clean eighth edition with a few helpful notes in pencil.", "Books",
                    "38.00", ItemCondition.GOOD),
            new DemoListing("Adjustable desk lamp",
                    "Warm and cool light modes; ideal for a dorm study desk.", "Dorm",
                    "18.50", ItemCondition.LIKE_NEW),
            new DemoListing("TI-84 Plus calculator",
                    "Works perfectly and includes a fresh set of batteries.", "Electronics",
                    "54.00", ItemCondition.GOOD),
            new DemoListing("Clip-on reading lamp",
                    "USB-powered lamp with three brightness levels and a flexible neck.", "Dorm",
                    "12.00", ItemCondition.LIKE_NEW),
            new DemoListing("Compact LED floor lamp",
                    "Slim black floor lamp with a foot switch and energy-saving LED bulb.", "Dorm",
                    "22.00", ItemCondition.GOOD),
            new DemoListing("Rolling under-bed storage bins",
                    "Pair of low-profile bins that fit beneath most raised dorm beds.", "Dorm",
                    "16.00", ItemCondition.GOOD),
            new DemoListing("Quiet oscillating desk fan",
                    "Three-speed fan that is quiet enough to run while studying or sleeping.", "Dorm",
                    "14.50", ItemCondition.LIKE_NEW),
            new DemoListing("Memory foam chair cushion",
                    "Supportive charcoal cushion for long study sessions at a desk.", "Dorm",
                    "11.00", ItemCondition.GOOD),
            new DemoListing("Organic Chemistry textbook",
                    "Fourth edition with clean pages and the molecular model reference card.", "Books",
                    "29.00", ItemCondition.GOOD),
            new DemoListing("Introduction to Java Programming",
                    "Useful introductory text with practice exercises and no missing pages.", "Books",
                    "24.00", ItemCondition.FAIR),
            new DemoListing("USB-C docking hub",
                    "HDMI, Ethernet, SD card, and three USB ports in one aluminum hub.", "Electronics",
                    "27.50", ItemCondition.LIKE_NEW),
            new DemoListing("24-inch IPS monitor",
                    "1080p monitor with HDMI cable and height-adjustable stand included.", "Electronics",
                    "72.00", ItemCondition.GOOD),
            new DemoListing("Wireless mechanical keyboard",
                    "Compact keyboard with quiet tactile switches, Bluetooth, and USB-C.", "Electronics",
                    "36.00", ItemCondition.GOOD),
            new DemoListing("Purdue fleece hoodie",
                    "Black unisex medium hoodie, freshly washed with no stains or tears.", "Clothing",
                    "20.00", ItemCondition.GOOD),
            new DemoListing("Waterproof bike U-lock",
                    "Hardened steel lock with mounting bracket and two keys.", "Other",
                    "19.00", ItemCondition.LIKE_NEW));

    private final UserRepository users;
    private final ListingRepository listings;
    private final MongoTemplate mongo;

    public DemoDataInitializer(UserRepository users, ListingRepository listings, MongoTemplate mongo) {
        this.users = users;
        this.listings = listings;
        this.mongo = mongo;
    }

    @Override
    public void run(String... args) {
        UserAccount maya = ensureUser("Maya Chen", "maya@campus.edu", "North Campus");
        ensureUser("Noah Williams", "noah@campus.edu", "South Campus");

        DEMO_LISTINGS.stream()
                .filter(demo -> !listings.existsByTitleIgnoreCase(demo.title()))
                .forEach(demo -> insertMissingListing(demo, maya.getId()));
    }

    private UserAccount ensureUser(String name, String email, String campus) {
        return users.findByEmailIgnoreCase(email).orElseGet(() -> {
            try {
                return users.saveAndFlush(new UserAccount(name, email, campus));
            } catch (DataIntegrityViolationException exception) {
                // Another instance may have committed this demo user after our initial read.
                // The repository transaction has ended, so reread the winner in a fresh transaction.
                return users.findByEmailIgnoreCase(email).orElseThrow(() -> exception);
            }
        });
    }

    private void insertMissingListing(DemoListing demo, UUID sellerId) {
        Listing listing = demo.toListing(sellerId);
        String seedId = UUID.nameUUIDFromBytes(
                ("campusloop:demo-listing:" + demo.title()).getBytes(StandardCharsets.UTF_8)).toString();
        // A shared _id makes concurrent cold starts target the same document. Insert-only
        // updates also leave an already-sold seed untouched if our title check was stale.
        mongo.upsert(Query.query(Criteria.where("id").is(seedId)),
                new Update()
                        .setOnInsert("sellerId", listing.getSellerId())
                        .setOnInsert("title", listing.getTitle())
                        .setOnInsert("description", listing.getDescription())
                        .setOnInsert("category", listing.getCategory())
                        .setOnInsert("price", listing.getPrice())
                        .setOnInsert("condition", listing.getCondition())
                        .setOnInsert("status", listing.getStatus())
                        .setOnInsert("createdAt", listing.getCreatedAt()),
                Listing.class);
    }

    private record DemoListing(String title, String description, String category,
            String price, ItemCondition condition) {

        Listing toListing(UUID sellerId) {
            return new Listing(sellerId, title, description, category,
                    new BigDecimal(price), condition);
        }
    }
}
