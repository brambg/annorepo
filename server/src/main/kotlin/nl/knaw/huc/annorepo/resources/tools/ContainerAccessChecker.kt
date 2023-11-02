package nl.knaw.huc.annorepo.resources.tools

import java.security.Principal
import jakarta.ws.rs.NotAuthorizedException
import nl.knaw.huc.annorepo.api.Role
import nl.knaw.huc.annorepo.auth.RootUser
import nl.knaw.huc.annorepo.dao.ContainerUserDAO

class ContainerAccessChecker(private val containerUserDAO: ContainerUserDAO) {
    fun checkUserHasAdminRightsInThisContainer(userPrincipal: Principal?, containerName: String) {
        checkUserRightsInThisContainer(userPrincipal, containerName, setOf(Role.ADMIN), false)
    }

    fun checkUserHasEditRightsInThisContainer(userPrincipal: Principal?, containerName: String) {
        checkUserRightsInThisContainer(userPrincipal, containerName, setOf(Role.ADMIN, Role.EDITOR), false)
    }

    fun checkUserHasReadRightsInThisContainer(
        userPrincipal: Principal?,
        containerName: String,
        anonymousHasAccess: Boolean
    ) {
        checkUserRightsInThisContainer(
            userPrincipal,
            containerName,
            setOf(Role.ADMIN, Role.EDITOR, Role.GUEST),
            anonymousHasAccess
        )
    }

    private fun checkUserRightsInThisContainer(
        userPrincipal: Principal?,
        containerName: String,
        rolesWithAccessRights: Set<Role>,
        anonymousHasAccess: Boolean = false,
    ) {
        if (userPrincipal is RootUser) {
            return
        }

        if (userPrincipal != null) {
            val userName = userPrincipal.name
            val role = containerUserDAO.getUserRole(containerName, userName)
            if (role in rolesWithAccessRights) {
                return
            }
            throw NotAuthorizedException("User $userName does not have access rights to this endpoint")
        }
        if (!anonymousHasAccess) {
            throw NotAuthorizedException("No authentication found")
        }
    }

}