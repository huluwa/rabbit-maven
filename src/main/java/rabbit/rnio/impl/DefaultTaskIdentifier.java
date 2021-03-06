package rabbit.rnio.impl;

import rabbit.rnio.TaskIdentifier;

/** A basic immutable task identifier
 *
 * @author <a href="mailto:robo@khelekore.org">Robert Olofsson</a>
 */
public class DefaultTaskIdentifier implements TaskIdentifier {
    private final String groupId;
    private final String description;

    public DefaultTaskIdentifier(final String groupId, final String description) {
        this.groupId = groupId;
        this.description = description;
    }

    @Override
    public String getGroupId() {
        return groupId;
    }

    @Override
    public String getDescription() {
        return description;
    }
}
